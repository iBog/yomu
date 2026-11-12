package com.yomu.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayCoordinateMapperTest {
    @Test
    fun `params are identity when overlay origin is display origin`() {
        val params = OverlayCoordinateMapper.params(
            captureWidth = 1080,
            captureHeight = 2400,
            displayWidth = 1080,
            displayHeight = 2400,
            overlayScreenX = 0,
            overlayScreenY = 0
        )

        assertEquals(0f, params.offsetX)
        assertEquals(0f, params.offsetY)
        assertEquals(1f, params.scaleX)
        assertEquals(1f, params.scaleY)
    }

    @Test
    fun `map is identity when the frame is the display, even if the overlay window is shorter than it`() {
        val params = OverlayCoordinateMapper.params(
            captureWidth = 1080,
            captureHeight = 2340,
            displayWidth = 1080,
            displayHeight = 2340,
            overlayScreenX = 0,
            overlayScreenY = 0
        )

        val mapped = OverlayCoordinateMapper.map(
            floatArrayOf(100f, 600f, 300f, 900f),
            params
        )

        assertEquals(100f, mapped.left)
        assertEquals(600f, mapped.top)
        assertEquals(300f, mapped.right)
        assertEquals(900f, mapped.bottom)
    }

    @Test
    fun `map subtracts overlay screen offset`() {
        val params = OverlayCoordinateMapper.params(
            captureWidth = 1080,
            captureHeight = 2340,
            displayWidth = 1080,
            displayHeight = 2340,
            overlayScreenX = 0,
            overlayScreenY = 63
        )

        val mapped = OverlayCoordinateMapper.map(
            floatArrayOf(100f, 200f, 300f, 400f),
            params
        )

        assertEquals(100f, mapped.left)
        assertEquals(137f, mapped.top)
        assertEquals(300f, mapped.right)
        assertEquals(337f, mapped.bottom)
    }

    @Test
    fun `map scales a frame that is not the size of the display, and offsets`() {
        val params = OverlayCoordinateMapper.params(
            captureWidth = 1200,
            captureHeight = 800,
            displayWidth = 600,
            displayHeight = 400,
            overlayScreenX = 100,
            overlayScreenY = 0
        )

        val mapped = OverlayCoordinateMapper.map(
            floatArrayOf(200f, 100f, 600f, 300f),
            params
        )

        assertEquals(50f, mapped.left)
        assertEquals(50f, mapped.top)
        assertEquals(250f, mapped.right)
        assertEquals(150f, mapped.bottom)
    }

    @Test
    fun `a landscape frame the size of the landscape display maps one to one, whatever bars shrink the window`() {
        // 2772 x 1272 screen turned on its side; the overlay window starts below a 72 px status bar and clear of a 48 px side bar.
        val params = OverlayCoordinateMapper.params(
            captureWidth = 2772,
            captureHeight = 1272,
            displayWidth = 2772,
            displayHeight = 1272,
            overlayScreenX = 48,
            overlayScreenY = 72
        )

        val mapped = OverlayCoordinateMapper.map(floatArrayOf(1000f, 600f, 1400f, 700f), params)

        assertEquals(1f, params.scaleX)
        assertEquals(1f, params.scaleY)
        assertEquals(952f, mapped.left)
        assertEquals(528f, mapped.top)
    }

    @Test
    fun `a portrait frame left on a landscape display is stretched to it, not silently trusted`() {
        val params = OverlayCoordinateMapper.params(1272, 2772, 2772, 1272, 0, 0)

        assertEquals(2772f / 1272f, params.scaleX, 0.0001f)
        assertEquals(1272f / 2772f, params.scaleY, 0.0001f)
    }

    @Test
    fun `a box grown past the bottom slides back on screen`() {
        val clamped = OverlayCoordinateMapper.clampToCanvas(
            OverlayBounds(10f, 900f, 110f, 1100f),
            1000f,
            1000f
        )

        assertEquals(800f, clamped.top, 0.01f)
        assertEquals(1000f, clamped.bottom, 0.01f)
        assertEquals(10f, clamped.left, 0.01f)
    }

    @Test
    fun `a box grown past the top slides back on screen`() {
        val clamped = OverlayCoordinateMapper.clampToCanvas(
            OverlayBounds(10f, -60f, 110f, 140f),
            1000f,
            1000f
        )

        assertEquals(0f, clamped.top, 0.01f)
        assertEquals(200f, clamped.bottom, 0.01f)
    }

    @Test
    fun `a box taller than the canvas is pinned to the top`() {
        val clamped = OverlayCoordinateMapper.clampToCanvas(
            OverlayBounds(0f, -50f, 100f, 1200f),
            1000f,
            1000f
        )

        assertEquals(0f, clamped.top, 0.01f)
    }

    @Test
    fun `a box widened past the right edge slides back on screen`() {
        val clamped = OverlayCoordinateMapper.clampToCanvas(
            OverlayBounds(950f, 10f, 1100f, 110f),
            1000f,
            1000f
        )

        assertEquals(850f, clamped.left, 0.01f)
        assertEquals(1000f, clamped.right, 0.01f)
        assertEquals(10f, clamped.top, 0.01f)
    }
}
