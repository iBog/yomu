package com.yomu.pipeline

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.yomu.core.GlossaryEntry
import com.yomu.core.SourceLanguage
import com.yomu.core.TargetLanguage
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.bubble.bubbleRenderBoxes
import com.yomu.pipeline.bubble.centeredOutside
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.LineOcrEngine
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.ocr.OcrResult
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.typesetting.TypesetBubble
import com.yomu.pipeline.typesetting.Typesetter
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TranslationPipeline(
    private val bubbleDetector: BubbleDetector,
    private val ocrEngine: OcrEngine,
    private val contextAssembler: ContextAssembler,
    private val translationEngine: TranslationEngine,
    private val typesetter: Typesetter,
    /** Reads every source but Japanese; without it only Japanese pages can be read. */
    private val lineOcrEngine: LineOcrEngine? = null
) {

    companion object {
        private const val TAG = "TranslationPipeline"
    }

    enum class Stage {
        BUBBLE_DETECTION,
        OCR,
        CONTEXT_ASSEMBLY,
        TRANSLATION,
        DONE,
        ERROR
    }

    interface PipelineCallback {
        fun onStageProgress(stage: Stage, progress: Float)
        fun onError(stage: Stage, message: String)
        fun onComplete(result: PipelineResult)
    }

    private var currentStage = Stage.ERROR

    var modelPaths: ModelPaths? = null
    var fontSizeScale: Float = 1.0f
    /** Read at the start of every page, so a Settings change applies on the next capture (#227). */
    @Volatile var confidenceThreshold: Float = BubbleDetector.DEFAULT_CONFIDENCE_THRESHOLD

    /** Capture-to-typeset time of the most recent finished page, for the in-app resource readout (#422); null before any. */
    @Volatile
    var lastPageTotalMs: Long? = null
        private set

    fun getCurrentStage(): Stage = currentStage

    fun isModelLoaded(source: SourceLanguage = SourceLanguage.JAPANESE): Boolean = bubbleDetector.isModelLoaded() &&
        if (source == SourceLanguage.JAPANESE) ocrEngine.isModelLoaded() else lineOcrEngine?.isModelLoaded() == true

    suspend fun loadModels(paths: ModelPaths): Boolean = withContext(Dispatchers.IO) {
        val bubbleLoaded = bubbleDetector.isModelLoaded() || bubbleDetector.loadModel(paths.bubbleDetectionPath)
        val ocrLoaded = if (paths.ocrSource == SourceLanguage.JAPANESE) {
            ocrEngine.loadModel(paths.ocrEncoderPath, paths.ocrDecoderPath, paths.ocrVocabPath)
        } else {
            lineOcrEngine?.loadModel(paths.ocrEncoderPath, paths.ocrVocabPath, paths.ocrSource) == true
        }
        bubbleLoaded && ocrLoaded
    }

    /** Held by a page run, so a model is never unloaded while a native session is still using it. */
    private val inference = Mutex()

    /**
     * Serializes page processing with model unloading and emits OCR previews followed by typeset bubbles.
     * A bubble centered in one of [ignoredRegions], such as the status bar, is never read.
     * The page is read as [sourceLanguage] and translated into [targetLanguage], with [glossary] applied to its source text.
     * Callbacks run in the processing coroutine; UI callers must dispatch to the main thread.
     * Returns null on processing failure and propagates cancellation.
     */
    suspend fun processPage(
        bitmap: Bitmap,
        callback: PipelineCallback? = null,
        onOcrComplete: ((bubbleId: Int, ocrText: String, bounds: RectF) -> Unit)? = null,
        onBubble: ((TypesetBubble) -> Unit)? = null,
        ignoredRegions: List<RectF> = emptyList(),
        targetLanguage: TargetLanguage,
        glossary: List<GlossaryEntry> = emptyList(),
        sourceLanguage: SourceLanguage = SourceLanguage.JAPANESE
    ): PipelineResult? = inference.withLock {
        runPage(bitmap, callback, onOcrComplete, onBubble, ignoredRegions, PageLanguages(sourceLanguage, targetLanguage), glossary)
    }

    /** Releases the detector after any active page finishes using its native session. */
    suspend fun unloadDetection() = inference.withLock { bubbleDetector.release() }

    suspend fun unloadOcr() = inference.withLock {
        ocrEngine.release()
        lineOcrEngine?.release()
    }

    suspend fun unloadTranslation() = inference.withLock { translationEngine.close() }

    /**
     * Detects and reads a page, prepares render bounds, then typesets each answered bubble as it arrives.
     * Records first-bubble latency and returns final bubbles in detection order; reports failures through [callback].
     */
    private suspend fun runPage(
        bitmap: Bitmap,
        callback: PipelineCallback?,
        onOcrComplete: ((bubbleId: Int, ocrText: String, bounds: RectF) -> Unit)?,
        onBubble: ((TypesetBubble) -> Unit)?,
        ignoredRegions: List<RectF>,
        languages: PageLanguages,
        glossary: List<GlossaryEntry>
    ): PipelineResult? {
        val source = languages.source
        val startTime = System.currentTimeMillis()
        val pageWidth = bitmap.width
        val pageHeight = bitmap.height
        val stageTimesMs = linkedMapOf<Stage, Long>()
        var stageStart = startTime
        fun endStage(stage: Stage) {
            val now = System.currentTimeMillis()
            stageTimesMs[stage] = now - stageStart
            stageStart = now
        }

        try {
            if (!isModelLoaded(source)) {
                val paths = modelPaths
                if (paths == null) {
                    callback?.onError(Stage.BUBBLE_DETECTION, "Model paths not configured")
                    currentStage = Stage.ERROR
                    return null
                }
                currentStage = Stage.BUBBLE_DETECTION
                callback?.onStageProgress(Stage.BUBBLE_DETECTION, 0.0f)
                Log.i(TAG, "loadModels start")
                loadModels(paths)
                Log.i(TAG, "loadModels complete modelsLoaded=${isModelLoaded(source)}")
            }

            if (!isModelLoaded(source)) {
                callback?.onError(Stage.BUBBLE_DETECTION, "Failed to load models")
                currentStage = Stage.ERROR
                return null
            }

            currentStage = Stage.BUBBLE_DETECTION
            callback?.onStageProgress(Stage.BUBBLE_DETECTION, 0.0f)
            stageStart = System.currentTimeMillis()
            val bubbles = bubbleDetector.detect(bitmap, confidenceThreshold).centeredOutside(ignoredRegions)
            coroutineContext.ensureActive()
            endStage(Stage.BUBBLE_DETECTION)
            callback?.onStageProgress(Stage.BUBBLE_DETECTION, 0.2f)

            if (bubbles.isEmpty()) {
                callback?.onError(Stage.BUBBLE_DETECTION, "No bubbles detected on the page")
                currentStage = Stage.ERROR
                return null
            }

            currentStage = Stage.OCR
            callback?.onStageProgress(Stage.OCR, 0.2f)
            val reads = linkedMapOf<Int, OcrResult>()
            val ocrResults = mutableMapOf<Int, OcrResult>()
            val droppedReads = mutableMapOf<Int, String>()

            for ((index, bubble) in bubbles.withIndex()) {
                val cropBounds = CropBoundsCalculator.clamp(
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height,
                    left = bubble.boundingBox.left.toInt(),
                    top = bubble.boundingBox.top.toInt(),
                    width = bubble.boundingBox.width().toInt(),
                    height = bubble.boundingBox.height().toInt()
                )
                val bubbleBitmap = Bitmap.createBitmap(
                    bitmap,
                    cropBounds.x,
                    cropBounds.y,
                    cropBounds.width,
                    cropBounds.height
                )

                val result = if (source == SourceLanguage.JAPANESE) ocrEngine.extractText(bubbleBitmap) else lineOcrEngine?.extractText(bubbleBitmap)
                coroutineContext.ensureActive()
                if (result != null && source.isReadable(result.text)) {
                    reads[bubble.id] = result
                    // A garbled read comes back as confident wrong English, so its bubble keeps its art (#366).
                    if (result.unsure) {
                        Log.i(TAG, "Skipping unsure read bubble=${bubble.id} confidence=${result.confidence}")
                    } else {
                        ocrResults[bubble.id] = result
                        onOcrComplete?.invoke(bubble.id, result.text, bubble.boundingBox)
                    }
                } else if (result != null && result.text.isNotBlank()) {
                    droppedReads[bubble.id] = result.text
                }

                val progress = 0.2f + ((index + 1).toFloat() / bubbles.size) * 0.3f
                callback?.onStageProgress(Stage.OCR, progress)
            }
            endStage(Stage.OCR)

            currentStage = Stage.CONTEXT_ASSEMBLY
            callback?.onStageProgress(Stage.CONTEXT_ASSEMBLY, 0.5f)
            val pageContext = contextAssembler.assemble(
                bubbles = bubbles,
                ocrResults = ocrResults,
                page = bitmap
            )
            // Typeset into the bubble interior; boundingBox stays the detector's glyph box, which
            // OCR crops and panel grouping are tuned to. Computed before translation so each bubble
            // is typeset the moment it arrives.
            val detectedBounds = bubbles.associate { bubble ->
                bubble.id to floatArrayOf(
                    bubble.boundingBox.left,
                    bubble.boundingBox.top,
                    bubble.boundingBox.right,
                    bubble.boundingBox.bottom
                )
            }
            val bubbleBounds = bubbleRenderBoxes(bitmap, detectedBounds)
            typesetter.fontSizeScale = fontSizeScale
            endStage(Stage.CONTEXT_ASSEMBLY)
            callback?.onStageProgress(Stage.CONTEXT_ASSEMBLY, 0.6f)

            currentStage = Stage.TRANSLATION
            callback?.onStageProgress(Stage.TRANSLATION, 0.6f)
            // Only answered bubbles arrive, so an unanswered one gets no box and the reader sees
            // the art under it (#330).
            val arrived = mutableMapOf<Int, TypesetBubble>()
            var firstBubbleAt: Long? = null
            val translationResult = translationEngine.translate(pageContext.blocks, languages.target, glossary, source) { translated ->
                val typeset = typesetter.typeset(listOf(translated), bubbleBounds).single()
                if (firstBubbleAt == null) firstBubbleAt = System.currentTimeMillis()
                arrived[typeset.bubbleId] = typeset
                onBubble?.invoke(typeset)
            }
            coroutineContext.ensureActive()
            endStage(Stage.TRANSLATION)
            // Detection order, as the preview stacks them, so overlapping boxes keep their stacking.
            val typesetBubbles = bubbles.mapNotNull { arrived[it.id] }
            callback?.onStageProgress(Stage.TRANSLATION, 1.0f)

            currentStage = Stage.DONE

            val result = PipelineResult(
                typesetBubbles = typesetBubbles,
                translationResult = translationResult,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                totalTimeMs = System.currentTimeMillis() - startTime,
                timeToFirstBubbleMs = firstBubbleAt?.let { it - startTime },
                stageTimesMs = stageTimesMs,
                bubbleBounds = detectedBounds,
                reads = reads,
                droppedReads = droppedReads
            )

            lastPageTotalMs = result.totalTimeMs
            callback?.onComplete(result)
            return result

        } catch (e: CancellationException) {
            // A reader cancel (#76), not a failure: no error callback, no "Translation failed".
            throw e
        } catch (e: Exception) {
            callback?.onError(currentStage, e.message ?: "Unknown error")
            currentStage = Stage.ERROR
            return null
        }
    }

    fun release() {
        translationEngine.endSession()
    }

    fun close() {
        bubbleDetector.release()
        ocrEngine.release()
        lineOcrEngine?.release()
        translationEngine.close()
    }
}
