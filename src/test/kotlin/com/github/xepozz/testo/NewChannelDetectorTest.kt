package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ChannelOutputStore.Chunk
import com.github.xepozz.testo.tests.console.NewChannelDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewChannelDetectorTest {

    private val showAll: (Chunk) -> Boolean = { true }

    @Test
    fun namesAnUntabbedChannelOnce() {
        val detector = NewChannelDetector(emptyList(), showAll)

        assertEquals("bench-result", detector.offer(Chunk("table", "info", "bench-result")))
        assertNull(detector.offer(Chunk("more", "info", "bench-result")))
    }

    @Test
    fun skipsTabbedChannelsAndPlainOutput() {
        val detector = NewChannelDetector(listOf("sql"), showAll)

        assertNull(detector.offer(Chunk("select 1", null, "sql")))
        assertNull(detector.offer(Chunk("plain", null)))
    }

    @Test
    fun aHiddenChunkLeavesTheChannelForALaterVisibleOne() {
        val detector = NewChannelDetector(emptyList()) { it.level != "debug" }

        assertNull(detector.offer(Chunk("noise", "debug", "log")))
        assertEquals("log", detector.offer(Chunk("hello", "info", "log")))
    }
}
