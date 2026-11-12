package com.yomu.pipeline.bubble

import android.graphics.Bitmap
import com.yomu.ml.OnnxRuntime
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BubbleDetectorLoadTest {

    private val onnx = mock(OnnxRuntime::class.java).also { `when`(it.loadModel(anyString())).thenReturn(true) }
    private val page = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

    @Test
    fun `loading another model file releases the first and detects with the second`() {
        val detector = BubbleDetector(onnx)

        detector.loadModel("old.onnx")
        detector.loadModel("new.onnx")
        detector.detect(page, 0.25f)

        verify(onnx).releaseModel("old.onnx")
        verify(onnx).runBitmapInference("new.onnx", page, 0.25f)
    }
}
