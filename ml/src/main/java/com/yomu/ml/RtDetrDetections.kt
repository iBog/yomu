package com.yomu.ml

import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

// ogkalu/comic-text-and-bubble-detector (RT-DETR-v2): the page is stretched, not letterboxed, to a
// square, and the model scales its boxes back to the page through this second input.
internal const val RT_DETR_SIZES_INPUT = "orig_target_sizes"
private const val RT_DETR_INPUT_SIZE = 640
private const val RT_DETR_BOX_FIELDS = 4
private const val TAG = "RtDetrDetections"

// Class 0 frames the whole balloon; the pipeline crops text for OCR, so only the text classes count.
private val RT_DETR_TEXT_CLASSES = mapOf(1L to "text_bubble", 2L to "text_free")

/** Runs an RT-DETR text detector on [bitmap]; boxes come back as xyxy page pixels, highest confidence first. */
internal fun runRtDetrInference(
    env: OrtEnvironment,
    session: OrtSession,
    bitmap: Bitmap,
    confidenceThreshold: Float
): List<OnnxRuntime.Detection> = try {
    val resized = Bitmap.createScaledBitmap(bitmap, RT_DETR_INPUT_SIZE, RT_DETR_INPUT_SIZE, true)
    val pixels = IntArray(RT_DETR_INPUT_SIZE * RT_DETR_INPUT_SIZE)
    resized.getPixels(pixels, 0, RT_DETR_INPUT_SIZE, 0, 0, RT_DETR_INPUT_SIZE, RT_DETR_INPUT_SIZE)
    val imageShape = longArrayOf(1, 3, RT_DETR_INPUT_SIZE.toLong(), RT_DETR_INPUT_SIZE.toLong())
    val sizes = longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())
    OnnxTensor.createTensor(env, FloatBuffer.wrap(bitmapPixelsToCHW(pixels, RT_DETR_INPUT_SIZE, RT_DETR_INPUT_SIZE)), imageShape).use { image ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(1, 2)).use { size ->
            val imageName = session.getInputNames().first { it != RT_DETR_SIZES_INPUT }
            session.run(mapOf(imageName to image, RT_DETR_SIZES_INPUT to size)).use { result ->
                fun tensor(name: String) = result.get(name).orElse(null) as? OnnxTensor
                val labels = tensor("labels")?.getLongBuffer()?.let { buffer -> LongArray(buffer.remaining()).also { buffer.get(it) } }
                val boxes = tensor("boxes")?.getFloatBuffer()?.let(::readFloatBuffer)
                val scores = tensor("scores")?.getFloatBuffer()?.let(::readFloatBuffer)
                if (labels == null || boxes == null || scores == null) {
                    Log.e(TAG, "RT-DETR output is missing labels, boxes or scores")
                    emptyList()
                } else {
                    parseRtDetrDetections(labels, boxes, scores, confidenceThreshold)
                }
            }
        }
    }
} catch (e: Exception) {
    Log.e(TAG, "RT-DETR inference failed: ${e.message}", e)
    emptyList()
}

/** The text detections in RT-DETR's parallel outputs at or above [confidenceThreshold], highest confidence first. */
internal fun parseRtDetrDetections(
    labels: LongArray,
    boxes: FloatArray,
    scores: FloatArray,
    confidenceThreshold: Float
): List<OnnxRuntime.Detection> {
    if (labels.size != scores.size || boxes.size != scores.size * RT_DETR_BOX_FIELDS) return emptyList()
    return scores.indices
        .filter { scores[it] >= confidenceThreshold && labels[it] in RT_DETR_TEXT_CLASSES }
        .map { i ->
            OnnxRuntime.Detection(
                label = RT_DETR_TEXT_CLASSES.getValue(labels[i]),
                confidence = scores[i],
                bbox = boxes.copyOfRange(i * RT_DETR_BOX_FIELDS, (i + 1) * RT_DETR_BOX_FIELDS)
            )
        }
        .sortedByDescending { it.confidence }
}
