package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ChannelOutputStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit4 tests for [ChannelOutputStore] — it has no IDE platform dependencies.
 */
class ChannelOutputStoreTest {

    @Test
    fun channelsForReturnsChannelsInInsertionOrder() {
        val store = ChannelOutputStore()
        store.append("t1", "sql", "select 1", null)
        store.append("t1", "log", "hello", "info")
        store.append("t1", "sql", "select 2", null)

        val channels = store.channelsFor("t1")
        assertEquals(listOf("sql", "log"), channels.keys.toList())
        assertEquals(
            listOf("select 1", "select 2"),
            channels["sql"]!!.map { it.text }
        )
        assertEquals(listOf("hello"), channels["log"]!!.map { it.text })
        assertEquals("info", channels["log"]!!.first().level)
    }

    @Test
    fun channelsForReturnsDefensiveCopies() {
        val store = ChannelOutputStore()
        store.append("t1", "sql", "a", null)

        val first = store.channelsFor("t1")
        store.append("t1", "sql", "b", null)
        // The previously returned snapshot must not see the later append.
        assertEquals(listOf("a"), first["sql"]!!.map { it.text })
        // A fresh snapshot sees both.
        assertEquals(listOf("a", "b"), store.channelsFor("t1")["sql"]!!.map { it.text })
    }

    @Test
    fun allForKeepsArrivalOrderAcrossChannelAndNonChannel() {
        val store = ChannelOutputStore()
        store.appendAll("t1", "first", null)
        store.appendAll("t1", "second", "warn")
        store.appendAll("t1", "third", null)

        val all = store.allFor("t1")
        assertEquals(listOf("first", "second", "third"), all.map { it.text })
        assertEquals(listOf(null, "warn", null), all.map { it.level })
    }

    @Test
    fun outputForExcludesChannelChunks() {
        val store = ChannelOutputStore()
        store.append("t1", "sql", "channel-only", null)
        store.appendOutput("t1", "plain output", null)

        val output = store.outputFor("t1")
        assertEquals(listOf("plain output"), output.map { it.text })
    }

    @Test
    fun allForReturnsDefensiveCopy() {
        val store = ChannelOutputStore()
        store.appendAll("t1", "a", null)
        val snapshot = store.allFor("t1")
        store.appendAll("t1", "b", null)
        assertEquals(listOf("a"), snapshot.map { it.text })
    }

    @Test
    fun channelIconReturnsFirstNonNullAndPutIfAbsentSemantics() {
        val store = ChannelOutputStore()
        assertNull(store.channelIcon("sql"))
        store.setChannelIcon("sql", "first")
        store.setChannelIcon("sql", "second")
        // putIfAbsent — first wins.
        assertEquals("first", store.channelIcon("sql"))
    }

    @Test
    fun channelColorReturnsFirstAndPutIfAbsentSemantics() {
        val store = ChannelOutputStore()
        assertNull(store.channelColor("sql"))
        store.setChannelColor("sql", "#fff")
        store.setChannelColor("sql", "#000")
        assertEquals("#fff", store.channelColor("sql"))
    }

    @Test
    fun clearEmptiesEverythingIncludingIconAndColorMaps() {
        val store = ChannelOutputStore()
        store.append("t1", "sql", "a", null)
        store.appendAll("t1", "b", null)
        store.appendOutput("t1", "c", null)
        store.setChannelIcon("sql", "icon")
        store.setChannelColor("sql", "#fff")

        store.clear()

        assertTrue(store.channelsFor("t1").isEmpty())
        assertTrue(store.allFor("t1").isEmpty())
        assertTrue(store.outputFor("t1").isEmpty())
        assertNull(store.channelIcon("sql"))
        assertNull(store.channelColor("sql"))
    }

    @Test
    fun unknownKeysReturnEmptyNotNull() {
        val store = ChannelOutputStore()
        assertTrue(store.channelsFor("missing").isEmpty())
        assertTrue(store.allFor("missing").isEmpty())
        assertTrue(store.outputFor("missing").isEmpty())
    }
}
