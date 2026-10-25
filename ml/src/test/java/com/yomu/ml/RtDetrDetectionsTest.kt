package com.yomu.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RtDetrDetectionsTest {

    @Test
    fun `keeps text in and outside bubbles above the threshold, highest confidence first`() {
        val labels = longArrayOf(1L, 2L, 1L)
        val boxes = floatArrayOf(
            10f, 20f, 30f, 40f,
            50f, 60f, 70f, 80f,
            90f, 100f, 110f, 120f
        )
        val scores = floatArrayOf(0.60f, 0.95f, 0.20f)

        val detections = parseRtDetrDetections(labels, boxes, scores, confidenceThreshold = 0.25f)

        assertEquals(listOf(0.95f, 0.60f), detections.map { it.confidence })
        assertArrayEquals(floatArrayOf(50f, 60f, 70f, 80f), detections[0].bbox, 1e-6f)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f), detections[1].bbox, 1e-6f)
    }

    @Test
    fun `drops the bubble outline class, which frames a balloon rather than its text`() {
        val detections = parseRtDetrDetections(
            labels = longArrayOf(0L, 1L),
            boxes = floatArrayOf(0f, 0f, 100f, 100f, 10f, 10f, 90f, 90f),
            scores = floatArrayOf(0.99f, 0.50f),
            confidenceThreshold = 0.25f
        )

        assertEquals(1, detections.size)
        assertArrayEquals(floatArrayOf(10f, 10f, 90f, 90f), detections[0].bbox, 1e-6f)
    }

    @Test
    fun `labels each detection with the class it came from`() {
        val detections = parseRtDetrDetections(
            labels = longArrayOf(1L, 2L),
            boxes = FloatArray(8),
            scores = floatArrayOf(0.9f, 0.8f),
            confidenceThreshold = 0.25f
        )

        assertEquals(listOf("text_bubble", "text_free"), detections.map { it.label })
    }

    @Test
    fun `returns nothing when the outputs disagree in length`() {
        val detections = parseRtDetrDetections(
            labels = longArrayOf(1L, 1L),
            boxes = FloatArray(4),
            scores = floatArrayOf(0.9f, 0.9f),
            confidenceThreshold = 0.25f
        )

        assertEquals(emptyList<OnnxRuntime.Detection>(), detections)
    }
}
