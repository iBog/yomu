package com.yomu.pipeline.ocr

import android.graphics.Bitmap
import com.yomu.core.SourceLanguage
import com.yomu.ml.OnnxRuntime
import java.io.File

/**
 * Reads a bubble of horizontal text one line at a time with a PP-OCRv5 recognizer, for every
 * source but Japanese. The bubble is split into lines by its ink rows, since the recognizer reads
 * a single line; each line is scaled to the model's 48 px height and decoded with CTC.
 */
class LineOcrEngine(private val onnxRuntime: OnnxRuntime) {

    companion object {
        // Clean synthetic lines in every script scored 0.96 or more; blurred noise and dot fields
        // decoded to nothing or scored under 0.1. Re-check against page reports' ocrConfidence.
        const val MIN_CONFIDENCE = 0.7f
        private const val INPUT_HEIGHT = 48
        private const val MIN_INPUT_WIDTH = 16
        private const val CHANNELS = 3
        private const val INK_LUMINANCE = 128
        private const val MIN_LINE_HEIGHT = 4
        // A row is ink when this share of its pixels is dark, so a stray speck or outline does not open a line.
        private const val INK_ROW_SHARE = 0.005f
        private const val PAD_DIVISOR = 6
        private const val MIN_PAD = 2
    }

    private var modelPath: String? = null
    private var dict: List<String> = emptyList()
    private var source: SourceLanguage = SourceLanguage.LATIN_SCRIPT

    /** Loads the recognizer and its dictionary, the `character_dict` of its `inference.yml`. */
    fun loadModel(modelPath: String, configPath: String, source: SourceLanguage): Boolean {
        release()
        val config = File(configPath)
        val parsed = if (config.exists()) config.useLines { parseCharacterDict(it) } else emptyList()
        if (parsed.isEmpty() || !onnxRuntime.loadModel(modelPath)) return false
        this.modelPath = modelPath
        this.dict = parsed
        this.source = source
        return true
    }

    fun isModelLoaded(): Boolean = modelPath != null

    fun extractText(region: Bitmap): OcrResult? {
        val path = modelPath ?: return null
        val reads = lineCrops(region).mapNotNull { line -> readLine(path, line) }.filter { it.text.isNotEmpty() }
        val chars = reads.sumOf { it.text.length }
        val confidence = if (chars == 0) 0f else reads.sumOf { (it.confidence * it.text.length).toDouble() }.toFloat() / chars
        return OcrResult(
            text = joinLines(reads.map { it.text }, source.spacedLines),
            confidence = confidence,
            boundingBox = floatArrayOf(region.width.toFloat(), region.height.toFloat(), 0f, 0f),
            minConfidence = MIN_CONFIDENCE
        )
    }

    private fun readLine(path: String, line: Bitmap): CtcDecode? {
        val width = maxOf(line.width * INPUT_HEIGHT / maxOf(line.height, 1), MIN_INPUT_WIDTH)
        val scaled = Bitmap.createScaledBitmap(line, width, INPUT_HEIGHT, true)
        val name = onnxRuntime.getInputNames(path).firstOrNull() ?: return null
        val shape = longArrayOf(1, CHANNELS.toLong(), INPUT_HEIGHT.toLong(), width.toLong())
        val output = onnxRuntime.runInferenceNamed(path, mapOf(name to bgrPlanes(scaled)), mapOf(name to shape))
            ?.values?.firstOrNull() ?: return null
        val classes = onnxRuntime.getOutputNames(path).firstOrNull()
            ?.let { onnxRuntime.getOutputShape(path, it)?.lastOrNull() }
            ?.takeIf { it > 0 }?.toInt() ?: (dict.size + 2)
        return decodeCtc(output, classes, dict)
    }

    /** The model was trained on BGR planes scaled to [-1, 1]. */
    private fun bgrPlanes(bitmap: Bitmap): FloatArray {
        val plane = bitmap.width * bitmap.height
        val pixels = IntArray(plane).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val out = FloatArray(CHANNELS * plane)
        for (i in pixels.indices) {
            out[i] = scale(pixels[i] and 0xFF)
            out[i + plane] = scale((pixels[i] shr 8) and 0xFF)
            out[i + 2 * plane] = scale((pixels[i] shr 16) and 0xFF)
        }
        return out
    }

    private fun scale(channel: Int): Float = channel / 127.5f - 1f

    /** One crop per text line, top to bottom, each trimmed to its ink and padded by a sixth of its height. */
    private fun lineCrops(region: Bitmap): List<Bitmap> {
        val width = region.width
        val height = region.height
        val pixels = IntArray(width * height).also { region.getPixels(it, 0, width, 0, 0, width, height) }
        val luminance = IntArray(pixels.size) { i ->
            val p = pixels[i]
            (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        // Light text on a dark box reads as dark text on light once inverted.
        val inverted = luminance.average() < INK_LUMINANCE
        val ink = BooleanArray(pixels.size) { i -> (if (inverted) 255 - luminance[i] else luminance[i]) < INK_LUMINANCE }
        val minInk = maxOf(1, (width * INK_ROW_SHARE).toInt())
        val inkRows = (0 until height).map { y -> (0 until width).count { x -> ink[y * width + x] } >= minInk }
        return textLineBands(inkRows, MIN_LINE_HEIGHT).mapNotNull { band ->
            val columns = (0 until width).filter { x -> band.any { y -> ink[y * width + x] } }
            if (columns.isEmpty()) return@mapNotNull null
            val pad = maxOf(MIN_PAD, (band.last - band.first) / PAD_DIVISOR)
            val left = maxOf(columns.first() - pad, 0)
            val top = maxOf(band.first - pad, 0)
            val right = minOf(columns.last() + pad + 1, width)
            val bottom = minOf(band.last + pad + 1, height)
            Bitmap.createBitmap(region, left, top, right - left, bottom - top)
        }
    }

    fun release() {
        modelPath?.let { onnxRuntime.releaseModel(it) }
        modelPath = null
        dict = emptyList()
    }
}
