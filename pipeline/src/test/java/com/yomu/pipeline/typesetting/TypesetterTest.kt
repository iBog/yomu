package com.yomu.pipeline.typesetting

import android.graphics.Paint
import android.graphics.Typeface
import com.yomu.pipeline.translation.TranslatedBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypesetterTest {

    private val typesetter = Typesetter()

    private fun bounds(w: Float, h: Float) = floatArrayOf(0f, 0f, w, h)

    private fun bubble(id: Int, text: String) = TranslatedBubble(
        bubbleId = id,
        originalText = "",
        translatedText = text,
        answered = true
    )

    @Test
    fun `text lines fit within bubble height`() {
        val result = typesetter.typeset(
            listOf(bubble(1, "Hello world this is a test")),
            mapOf(1 to bounds(200f, 80f))
        )
        val b = result.first()
        assertTrue(b.lineHeight * b.textLines.size <= 80f * 1.1f)
    }

    @Test
    fun `a large box gets text larger than the small-bubble cap instead of a sea of padding`() {
        val text = "You catch your reflection in a shop window and don't recognise the walk: hips rolling, steps falling on a single line"
        val b = typesetter.typeset(listOf(bubble(1, text)), mapOf(1 to bounds(740f, 530f))).first()

        assertTrue("font ${b.fontSize}", b.fontSize > 36f)
        val textHeight = b.lineHeight * b.textLines.size
        assertTrue("text is ${textHeight}px in a 530px box", textHeight <= 530f)
    }

    @Test
    fun `a text panel keeps a thin margin and a bubble the wide one`() {
        assertEquals(0.04f, typesetter.paddingFor(bounds(740f, 530f)), 0.0001f)
        assertEquals(0.1f, typesetter.paddingFor(bounds(200f, 150f)), 0.0001f)
        assertEquals(0.1f, typesetter.paddingFor(bounds(900f, 200f)), 0.0001f)
    }

    @Test
    fun `a small bubble keeps the font cap`() {
        val b = typesetter.typeset(listOf(bubble(1, "Yes!")), mapOf(1 to bounds(200f, 150f))).first()

        assertTrue("font ${b.fontSize}", b.fontSize <= 36f)
    }

    @Test
    fun `no line exceeds bubble width`() {
        val result = typesetter.typeset(
            listOf(bubble(1, "Hello world this is a longer translation test")),
            mapOf(1 to bounds(150f, 100f))
        )
        val b = result.first()
        val maxWidth = 150f
        for (line in b.textLines) {
            assertTrue("Line '$line' must fit in width", line.length <= maxWidth.toInt() + 30)
        }
    }

    @Test
    fun `ocr special tokens are stripped`() {
        val result = typesetter.typeset(
            listOf(bubble(1, "[CLS] Hello world [SEP] [PAD]")),
            mapOf(1 to bounds(200f, 100f))
        )
        val cleaned = result.first().translatedText
        assertTrue(!cleaned.contains("[CLS]"))
        assertTrue(!cleaned.contains("[SEP]"))
        assertTrue(!cleaned.contains("[PAD]"))
        assertEquals("Hello world", cleaned)
    }

    @Test
    fun `long word without spaces produces at least one line`() {
        val longWord = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val result = typesetter.typeset(
            listOf(bubble(1, longWord)),
            mapOf(1 to bounds(80f, 200f))
        )
        val b = result.first()
        assertTrue("Should produce at least one line", b.textLines.isNotEmpty())
        assertTrue("All lines should be non-empty", b.textLines.all { it.isNotEmpty() })
    }

    @Test
    fun `default background is white and text is black`() {
        val result = typesetter.typeset(
            listOf(bubble(1, "Test")),
            mapOf(1 to bounds(100f, 60f))
        )
        val b = result.first()
        assertEquals(0xF0FFFFFF.toInt(), b.backgroundColor)
        assertEquals(0xFF000000.toInt(), b.textColor)
    }

    @Test
    fun `repeated whitespace is collapsed`() {
        val result = typesetter.typeset(
            listOf(bubble(1, "Hello   world\n\ntest")),
            mapOf(1 to bounds(200f, 100f))
        )
        assertTrue(!result.first().translatedText.contains("  "))
        assertTrue(!result.first().translatedText.contains("\n"))
    }

    private fun assertFitsDrawnBox(b: TypesetBubble) {
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT
            textSize = b.fontSize
        }
        val boxWidth = (b.boundingBox[2] - b.boundingBox[0]) * 0.8f
        val boxHeight = (b.boundingBox[3] - b.boundingBox[1]) * 0.8f
        val textHeight = b.lineHeight * b.textLines.size
        assertTrue("Text height $textHeight must fit box height $boxHeight", textHeight <= boxHeight + 0.01f)
        for (line in b.textLines) {
            assertTrue("Line '$line' must fit box width", paint.measureText(line) <= boxWidth + 0.01f)
        }
    }

    @Test
    fun `long text in a small box keeps every character`() {
        val text = "The quick brown fox jumps over the lazy dog again and again and again"
        val b = typesetter.typeset(
            listOf(bubble(1, text)),
            mapOf(1 to bounds(40f, 40f))
        ).first()

        assertEquals(text, b.textLines.joinToString(" "))
        assertFitsDrawnBox(b)
    }

    @Test
    fun `box grows instead of dropping lines`() {
        val b = typesetter.typeset(
            listOf(bubble(1, "The quick brown fox jumps over the lazy dog. ".repeat(3).trim())),
            mapOf(1 to bounds(40f, 40f))
        ).first()

        assertTrue("Box should have grown", b.boundingBox[3] - b.boundingBox[1] > 40f)
        assertEquals("Growth is centred", 20f, (b.boundingBox[1] + b.boundingBox[3]) / 2f, 0.01f)
    }

    @Test
    fun `text beyond the growth budget is ellipsized not silently dropped`() {
        val b = typesetter.typeset(
            listOf(bubble(1, "word ".repeat(400).trim())),
            mapOf(1 to bounds(40f, 40f))
        ).first()

        assertTrue("Cut must be visible", b.textLines.last().endsWith("\u2026"))
        assertFitsDrawnBox(b)
    }

    @Test
    fun `scale above one enlarges text in a tight bubble and grows its box`() {
        val text = "Hello world this is a test of a longer translated line"
        val plain = Typesetter(fontSizeScale = 1.0f).typeset(
            listOf(bubble(1, text)),
            mapOf(1 to bounds(60f, 60f))
        ).first()
        val scaled = Typesetter(fontSizeScale = 2.0f).typeset(
            listOf(bubble(1, text)),
            mapOf(1 to bounds(60f, 60f))
        ).first()

        assertTrue("Scale must still enlarge tight bubbles", scaled.fontSize > plain.fontSize)
        assertTrue(
            "Box absorbs the larger text",
            scaled.boundingBox[3] - scaled.boundingBox[1] > plain.boundingBox[3] - plain.boundingBox[1]
        )
        assertFitsDrawnBox(scaled)
    }

    @Test
    fun `scale above one keeps the fit invariant`() {
        for (scale in listOf(1.0f, 1.5f, 2.0f, 4.0f)) {
            val b = Typesetter(fontSizeScale = scale).typeset(
                listOf(bubble(1, "Hello world this is a test of a longer translated line")),
                mapOf(1 to bounds(200f, 100f))
            ).first()
            assertFitsDrawnBox(b)
        }
    }

    // Robolectric's Paint measures one unit per character, so these boxes are sized in characters.
    @Test
    fun `short word in a box narrower than it widens instead of breaking letters`() {
        val b = typesetter.typeset(
            listOf(bubble(1, "Yes!")),
            mapOf(1 to floatArrayOf(100f, 0f, 103f, 40f))
        ).first()

        assertEquals(listOf("Yes!"), b.textLines)
        assertEquals("Widening is centred", 101.5f, (b.boundingBox[0] + b.boundingBox[2]) / 2f, 0.01f)
        assertFitsDrawnBox(b)
    }

    @Test
    fun `untranslated japanese wraps by character instead of widening`() {
        val b = typesetter.typeset(
            listOf(bubble(1, "ジュンの適当発言")),
            mapOf(1 to floatArrayOf(100f, 0f, 103f, 40f))
        ).first()

        assertEquals(103f - 100f, b.boundingBox[2] - b.boundingBox[0], 0.01f)
        assertTrue("Wraps onto several lines", b.textLines.size > 1)
    }
}
