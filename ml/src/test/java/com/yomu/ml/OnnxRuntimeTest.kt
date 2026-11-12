package com.yomu.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OnnxRuntimeTest {

    @Test
    fun bitmapPixelsToCHW_writesRThenGThenBPlanes() {
        val pixels = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())

        val chw = bitmapPixelsToCHW(pixels, width = 2, height = 1)

        assertEquals(6, chw.size)
        assertEquals(0x11 / 255f, chw[0], 1e-6f)
        assertEquals(0x44 / 255f, chw[1], 1e-6f)
        assertEquals(0x22 / 255f, chw[2], 1e-6f)
        assertEquals(0x55 / 255f, chw[3], 1e-6f)
        assertEquals(0x33 / 255f, chw[4], 1e-6f)
        assertEquals(0x66 / 255f, chw[5], 1e-6f)
    }

    @Test
    fun parseRtDetrDetections_keepsOnlyTextClassesWithTheirPagePixelBoxes() {
        val detections = parseRtDetrDetections(
            labels = longArrayOf(0, 1, 2),
            boxes = floatArrayOf(
                10f, 20f, 300f, 400f,
                40f, 50f, 120f, 260f,
                500f, 600f, 700f, 650f
            ),
            scores = floatArrayOf(0.95f, 0.80f, 0.90f),
            confidenceThreshold = 0.25f
        )

        assertEquals(listOf("text_bubble", "text_free"), detections.map { it.label })
        assertArrayEquals(floatArrayOf(40f, 50f, 120f, 260f), detections[0].bbox, 0f)
        assertArrayEquals(floatArrayOf(500f, 600f, 700f, 650f), detections[1].bbox, 0f)
    }

    @Test
    fun parseRtDetrDetections_holdsTextFreeToAFloorOfOneHalf() {
        val detections = parseRtDetrDetections(
            labels = longArrayOf(1, 2, 2),
            boxes = FloatArray(12),
            scores = floatArrayOf(0.30f, 0.49f, 0.50f),
            confidenceThreshold = 0.25f
        )

        assertEquals(listOf("text_bubble" to 0.30f, "text_free" to 0.50f), detections.map { it.label to it.confidence })
    }
}
