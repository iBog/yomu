package com.yomu.app.overlay

data class OverlayBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

object OverlayCoordinateMapper {
    data class MapParams(
        val offsetX: Float,
        val offsetY: Float,
        val scaleX: Float,
        val scaleY: Float
    )

    /**
     * A captured frame is laid over the screen, so the scale is the screen over the frame: 1 while the frame is the
     * size of the display, whatever the overlay window's own size is (system bars and cutouts shrink the window, not
     * the picture). The window's place on the screen is the offset.
     */
    fun params(
        captureWidth: Int,
        captureHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        overlayScreenX: Int,
        overlayScreenY: Int
    ): MapParams {
        val scaleX = if (captureWidth > 0 && displayWidth > 0) displayWidth.toFloat() / captureWidth else 1f
        val scaleY = if (captureHeight > 0 && displayHeight > 0) displayHeight.toFloat() / captureHeight else 1f
        return MapParams(
            offsetX = overlayScreenX.toFloat(),
            offsetY = overlayScreenY.toFloat(),
            scaleX = scaleX,
            scaleY = scaleY
        )
    }

    // A bubble grown to hold its text can reach past the canvas; slide it back so no line is
    // drawn off-screen. A box larger than the canvas is pinned to the top-left.
    fun clampToCanvas(bounds: OverlayBounds, canvasWidth: Float, canvasHeight: Float): OverlayBounds {
        val shiftX = slideInside(bounds.left, bounds.right, canvasWidth)
        val shiftY = slideInside(bounds.top, bounds.bottom, canvasHeight)
        return OverlayBounds(
            left = bounds.left + shiftX,
            top = bounds.top + shiftY,
            right = bounds.right + shiftX,
            bottom = bounds.bottom + shiftY
        )
    }

    private fun slideInside(start: Float, end: Float, size: Float): Float = when {
        start < 0f -> -start
        end > size -> size - end
        else -> 0f
    }

    fun map(bounds: FloatArray, params: MapParams): OverlayBounds {
        return OverlayBounds(
            left = (bounds[0] - params.offsetX) * params.scaleX,
            top = (bounds[1] - params.offsetY) * params.scaleY,
            right = (bounds[2] - params.offsetX) * params.scaleX,
            bottom = (bounds[3] - params.offsetY) * params.scaleY
        )
    }
}
