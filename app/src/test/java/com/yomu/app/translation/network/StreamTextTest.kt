package com.yomu.app.translation.network

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamTextTest {
    private fun strip(vararg pieces: String): String {
        val stripper = ThinkStripper()
        return pieces.joinToString("") { stripper.feed(it) } + stripper.finish()
    }

    @Test
    fun `text without a thought passes through whole`() {
        assertEquals("[1] Hi\n[2] Bye", strip("[1] Hi\n", "[2] Bye"))
    }

    @Test
    fun `a think block is dropped, in one piece or across many`() {
        assertEquals("[1] Hi", strip("<think>hmm</think>[1] Hi"))
        assertEquals("[1] Hi", strip("<th", "ink>he", "re</thi", "nk>[1] ", "Hi"))
    }

    @Test
    fun `a less-than sign that is not a tag is kept`() {
        assertEquals("1 < 2 and <b>", strip("1 < 2 ", "and <", "b>"))
        assertEquals("ends with <thi", strip("ends with <thi"))
    }

    @Test
    fun `a thought that never closes shows nothing`() {
        assertEquals("before", strip("before<think>never ends"))
    }

    @Test
    fun `lines are handed over whole, once their newline has come`() {
        val lines = mutableListOf<String>()
        val buffer = LineBuffer { lines += it }

        buffer.feed("[1] Hel")
        assertEquals(emptyList<String>(), lines)
        buffer.feed("lo\r\n[2] By")
        assertEquals(listOf("[1] Hello"), lines)
        buffer.feed("e")
        buffer.finish()

        assertEquals(listOf("[1] Hello", "[2] Bye"), lines)
    }

    @Test
    fun `finishing with nothing pending hands over nothing`() {
        val lines = mutableListOf<String>()
        val buffer = LineBuffer { lines += it }
        buffer.feed("[1] Hi\n")
        buffer.finish()

        assertEquals(listOf("[1] Hi"), lines)
    }
}
