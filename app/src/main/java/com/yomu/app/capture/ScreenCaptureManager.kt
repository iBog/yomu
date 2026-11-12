package com.yomu.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenCaptureManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CAPTURE_TIMEOUT_MS = 4_000L
        private const val CAPTURE_POLL_INTERVAL_MS = 80L
    }

    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    private val windowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionManager: MediaProjectionManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    var isProjectionActive: Boolean = false
        private set

    fun getProjectionManager(): MediaProjectionManager {
        if (projectionManager == null) {
            projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
        }
        return projectionManager!!
    }

    /** Starts capturing from [projection]; [onEnded] runs when Android ends it, such as on screen lock. */
    fun startProjection(projection: MediaProjection, onEnded: () -> Unit) {
        mediaProjection?.stop()
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Yomu's own stop also lands here, after this projection was already let go or replaced.
                if (mediaProjection !== projection) return
                stopProjection()
                onEnded()
            }
        }, mainHandler)

        val display = realDisplaySize(windowManager)
        captureWidth = display.x
        captureHeight = display.y
        captureDensity = context.resources.displayMetrics.densityDpi
        val width = captureWidth
        val height = captureHeight
        val density = captureDensity

        imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "YomuScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )

        isProjectionActive = true
    }

    /**
     * Makes the capture the size of the display as it is turned now. The virtual display keeps the size it was made at,
     * so after a rotation it would squeeze the screen into the old shape and every box found on it would sit in the
     * wrong place. A new reader goes with the new size, so no frame of the old shape is ever read.
     */
    fun matchDisplay() {
        val display = realDisplaySize(windowManager)
        val mirror = virtualDisplay ?: return
        if (display.x == captureWidth && display.y == captureHeight) return
        val reader = ImageReader.newInstance(display.x, display.y, PixelFormat.RGBA_8888, 2)
        mirror.resize(display.x, display.y, captureDensity)
        mirror.surface = reader.surface
        imageReader?.close()
        imageReader = reader
        captureWidth = display.x
        captureHeight = display.y
    }

    fun captureScreen(onBitmapReady: (Bitmap?) -> Unit) {
        matchDisplay()
        val reader = imageReader
        if (!isProjectionActive || reader == null) {
            Log.i(TAG, "capture projectionActive=$isProjectionActive attempts=0 gotFrame=false width=0 height=0 timeoutFired=false")
            onBitmapReady(null)
            return
        }

        var attempts = 0
        var finished = false
        val startTimeMs = System.currentTimeMillis()
        val timeoutRunnable = Runnable {
            if (finished) return@Runnable
            finished = true
            Log.i(TAG, "capture projectionActive=$isProjectionActive attempts=$attempts gotFrame=false width=0 height=0 timeoutFired=true")
            onBitmapReady(null)
        }

        fun finish(bitmap: Bitmap?, gotFrame: Boolean, width: Int, height: Int, timeoutFired: Boolean) {
            if (finished) return
            finished = true
            mainHandler.removeCallbacks(timeoutRunnable)
            Log.i(
                TAG,
                "capture projectionActive=$isProjectionActive attempts=$attempts gotFrame=$gotFrame width=$width height=$height timeoutFired=$timeoutFired"
            )
            onBitmapReady(bitmap)
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (finished) return
                attempts += 1
                if (!isProjectionActive) {
                    finish(bitmap = null, gotFrame = false, width = 0, height = 0, timeoutFired = false)
                    return
                }
                val bitmap = acquireBitmap(reader)
                if (bitmap != null) {
                    finish(bitmap = bitmap, gotFrame = true, width = bitmap.width, height = bitmap.height, timeoutFired = false)
                    return
                }
                val elapsedMs = System.currentTimeMillis() - startTimeMs
                if (elapsedMs >= CAPTURE_TIMEOUT_MS) {
                    finish(bitmap = null, gotFrame = false, width = 0, height = 0, timeoutFired = true)
                    return
                }
                mainHandler.postDelayed(this, CAPTURE_POLL_INTERVAL_MS)
            }
        }

        mainHandler.postDelayed(timeoutRunnable, CAPTURE_TIMEOUT_MS)
        mainHandler.post(pollRunnable)
    }

    private fun acquireBitmap(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropWidth = image.width
            val cropHeight = image.height
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, cropWidth, cropHeight)
            bitmap.recycle()
            cropped
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    fun stopProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        isProjectionActive = false
    }
}
