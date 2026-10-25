package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import com.yomu.ml.OnnxRuntime
import java.io.File

data class OcrResult(
    val text: String,
    /** The decoder's mean probability for the tokens it chose, the stop token included. */
    val confidence: Float,
    val boundingBox: FloatArray,
    /** The reader's own floor: each OCR model scores its reads on its own scale. */
    val minConfidence: Float = OcrEngine.MIN_CONFIDENCE
) {
    /** A read this unsure is likely garbled, art or a text-free balloon, and is not worth translating (#366). */
    val unsure: Boolean get() = confidence < minConfidence
}

class OcrEngine(private val onnxRuntime: OnnxRuntime) {

    companion object {
        // Picked on 210 emulator reads of 18 OpenMantra pages (#366). Five of the six garbled
        // reads scored 0.57-0.84 and no clean read went below 0.87; the sixth, half garbled, scored 0.93
        // among clean lines. Re-check against page reports' ocrConfidence when OCR or its decoding changes.
        const val MIN_CONFIDENCE = 0.85f
        private const val INPUT_SIZE = 224
        private const val CHANNELS = 3
        // Fallback only: this model's [SEP] is not reliably at index 102 (the English-BERT id).
        // The real stop id is resolved from the loaded vocab by string; see sepTokenId.
        private const val EOS_TOKEN_ID = 102
        private const val SEP_TOKEN = "[SEP]"
        private const val NORMALIZATION_MEAN = 0.5f
        private const val NORMALIZATION_STD = 0.5f
        // Margin kept either side of a column group, so its outer strokes are not clipped.
        private const val COLUMN_PAD = 4
    }

    private var encoderLoaded = false
    private var decoderLoaded = false
    private var vocabLoaded = false
    private var encoderPath: String? = null
    private var decoderPath: String? = null
    private var vocabPath: String? = null
    private var vocab: Map<Int, String>? = null
    private var sepTokenId: Int = EOS_TOKEN_ID

    fun loadModel(encoderPath: String, decoderPath: String, vocabPath: String): Boolean {
        this.encoderPath = encoderPath
        this.decoderPath = decoderPath
        this.vocabPath = vocabPath

        encoderLoaded = onnxRuntime.loadModel(encoderPath)
        decoderLoaded = onnxRuntime.loadModel(decoderPath)
        vocabLoaded = loadVocab(vocabPath)

        return encoderLoaded && decoderLoaded && vocabLoaded
    }

    fun isModelLoaded(): Boolean = encoderLoaded && decoderLoaded && vocabLoaded

    /** Reads [region] whole, or a block of many vertical columns a few columns at a time ([verticalColumnGroups]). */
    fun extractText(region: Bitmap): OcrResult? {
        if (!isModelLoaded()) return null
        val groups = verticalColumnGroups(luminanceOf(region), region.width, region.height)
        if (groups.size < 2) return readWhole(region)
        val reads = groups.map { columns ->
            val left = (columns.first - COLUMN_PAD).coerceAtLeast(0)
            val right = (columns.last + COLUMN_PAD + 1).coerceAtMost(region.width)
            readWhole(Bitmap.createBitmap(region, left, 0, right - left, region.height)) ?: return null
        }
        return joinColumnReads(reads).copy(boundingBox = floatArrayOf(region.width.toFloat(), region.height.toFloat(), 0f, 0f))
    }

    private fun readWhole(region: Bitmap): OcrResult? {
        val encoderPath = encoderPath ?: return null
        val decoderPath = decoderPath ?: return null
        val vocab = vocab ?: return null

        val pixelValues = preprocessImage(region)
        val encoderOutput = runEncoder(encoderPath, pixelValues) ?: return null
        val encoderOutputName = onnxRuntime.getOutputNames(encoderPath).firstOrNull() ?: return null
        val encoderOutputShape = resolveDynamicShape(
            shape = onnxRuntime.getOutputShape(encoderPath, encoderOutputName),
            values = encoderOutput,
            fallback = longArrayOf(1L, 197L, 192L)
        )

        val decoded = runDecoder(decoderPath, encoderOutput, encoderOutputShape)

        return OcrResult(
            text = decodeTokens(decoded.tokenIds, vocab),
            confidence = decoded.confidence,
            boundingBox = floatArrayOf(
                region.width.toFloat(),
                region.height.toFloat(),
                0f,
                0f
            )
        )
    }

    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val floatArray = FloatArray(CHANNELS * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val planeSize = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            floatArray[i] = normalize(r)
            floatArray[i + planeSize] = normalize(g)
            floatArray[i + 2 * planeSize] = normalize(b)
        }

        return floatArray
    }

    private fun normalize(value: Float): Float {
        return (value - NORMALIZATION_MEAN) / NORMALIZATION_STD
    }

    private fun runEncoder(encoderPath: String, pixelValues: FloatArray): FloatArray? {
        val inputNames = onnxRuntime.getInputNames(encoderPath)
        if (inputNames.isEmpty()) return null

        val inputs = mutableMapOf<String, FloatArray>()
        val inputShapes = mutableMapOf<String, LongArray>()
        val inputShape = longArrayOf(1, CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

        for (name in inputNames) {
            if (isPixelValuesInput(name)) {
                inputs[name] = pixelValues
                inputShapes[name] = inputShape
            } else {
                val shape = onnxRuntime.getInputShape(encoderPath, name) ?: inputShape
                inputs[name] = FloatArray(shape.totalElements()) { 0f }
                inputShapes[name] = shape
            }
        }

        val outputs = onnxRuntime.runInferenceNamed(encoderPath, inputs, inputShapes)
        return outputs?.values?.firstOrNull()
    }

    private fun runDecoder(decoderPath: String, encoderOutput: FloatArray, encoderOutputShape: LongArray): GreedyDecode {
        val decode = onnxRuntime.startPastKeyValueDecode(decoderPath, encoderOutput, encoderOutputShape)
            ?: return GreedyDecode(emptyList(), 0f)
        return decode.use {
            decodeGreedy(sepTokenId) { token, past ->
                val step = decode.step(token, past) ?: return@decodeGreedy null
                DecoderStep(chooseToken(step.logits, 0, step.logits.size), step.presents)
            }
        }
    }

    private fun loadVocab(vocabPath: String): Boolean {
        return try {
            val file = File(vocabPath)
            if (!file.exists()) return false
            val loadedVocab = file.readLines().mapIndexed { index, line ->
                index to line.trim()
            }.toMap()
            vocab = loadedVocab
            sepTokenId = loadedVocab.entries.firstOrNull { it.value == SEP_TOKEN }?.key ?: EOS_TOKEN_ID
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveDynamicShape(
        shape: LongArray?,
        values: FloatArray,
        fallback: LongArray
    ): LongArray {
        val resolved = (shape ?: fallback).copyOf()
        for (index in resolved.indices) {
            if (resolved[index] <= 0L) {
                resolved[index] = fallback.getOrNull(index) ?: 1L
            }
        }
        val resolvedElements = resolved.totalElements()
        if (resolvedElements == values.size) {
            return resolved
        }
        if (resolved.size == 3 && resolved[1] > 0L && resolved[2] > 0L) {
            val trailing = resolved[1] * resolved[2]
            if (trailing > 0L && values.size % trailing == 0L) {
                resolved[0] = values.size / trailing
            }
        }
        return resolved
    }

    private fun isPixelValuesInput(name: String): Boolean {
        return name == "pixel_values" || name.contains("pixel")
    }

    private fun LongArray.totalElements(): Int {
        var total = 1L
        for (dim in this) {
            total *= dim.coerceAtLeast(1L)
        }
        return total.toInt()
    }

    fun release() {
        encoderPath?.let { onnxRuntime.releaseModel(it) }
        decoderPath?.let { onnxRuntime.releaseModel(it) }
        encoderPath = null
        decoderPath = null
        vocabPath = null
        vocab = null
        encoderLoaded = false
        decoderLoaded = false
        vocabLoaded = false
    }
}
