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
    fun setHeaderThenHeaderReturnsIt() {
        val store = ChannelOutputStore()
        val chunks = listOf(ChannelOutputStore.Chunk("cmd\n", null))
        store.setHeader(chunks)
        assertEquals(chunks, store.header())
    }

    @Test
    fun defaultHeaderIsEmpty() {
        val store = ChannelOutputStore()
        assertTrue(store.header().isEmpty())
    }

    @Test
    fun headerSurvivesClearWhilePerTestMapsAreEmptied() {
        val store = ChannelOutputStore()
        val header = listOf(ChannelOutputStore.Chunk("cmd\n", null))
        store.setHeader(header)
        store.append("t1", "sql", "a", null)
        store.appendAll("t1", "b", null)
        store.appendOutput("t1", "c", null)

        store.clear()

        // Per-test maps are emptied...
        assertTrue(store.channelsFor("t1").isEmpty())
        assertTrue(store.allFor("t1").isEmpty())
        assertTrue(store.outputFor("t1").isEmpty())
        // ...but the header deliberately survives clear().
        assertEquals(header, store.header())
    }

    @Test
    fun setHeaderOverwritesPreviousHeader() {
        val store = ChannelOutputStore()
        store.setHeader(listOf(ChannelOutputStore.Chunk("first\n", null)))
        val second = listOf(ChannelOutputStore.Chunk("second\n", "info"))
        store.setHeader(second)
        assertEquals(second, store.header())
    }

    @Test
    fun unknownKeysReturnEmptyNotNull() {
        val store = ChannelOutputStore()
        assertTrue(store.channelsFor("missing").isEmpty())
        assertTrue(store.allFor("missing").isEmpty())
        assertTrue(store.outputFor("missing").isEmpty())
    }

    @Test
    fun attachAllReplaysExistingThenStreamsLiveInOrder() {
        val store = ChannelOutputStore()
        store.appendAll("t1", "past-1", null)
        store.appendAll("t1", "past-2", null)

        val seen = mutableListOf<String>()
        store.attachAll("t1") { seen.add(it.text) }
        // Replay delivered the backlog immediately.
        assertEquals(listOf("past-1", "past-2"), seen)

        store.appendAll("t1", "live-1", null)
        store.appendAll("t1", "live-2", null)
        // Later appends stream straight to the sink, after the replay, in order.
        assertEquals(listOf("past-1", "past-2", "live-1", "live-2"), seen)
    }

    @Test
    fun attachAllDetachStopsDelivery() {
        val store = ChannelOutputStore()
        val seen = mutableListOf<String>()
        val detach = store.attachAll("t1") { seen.add(it.text) }

        store.appendAll("t1", "a", null)
        detach()
        store.appendAll("t1", "b", null)

        assertEquals(listOf("a"), seen)
    }

    @Test
    fun attachChannelReplaysAndStreamsOnlyItsChannel() {
        val store = ChannelOutputStore()
        store.append("t1", "sql", "select 1", null)

        val seen = mutableListOf<String>()
        store.attachChannel("t1", "sql") { seen.add(it.text) }
        store.append("t1", "sql", "select 2", null)
        store.append("t1", "log", "ignored", null)

        assertEquals(listOf("select 1", "select 2"), seen)
    }

    @Test
    fun multipleSinksEachReceiveLiveChunks() {
        val store = ChannelOutputStore()
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        store.attachAll("t1") { a.add(it.text) }
        store.attachAll("t1") { b.add(it.text) }

        store.appendAll("t1", "x", null)

        assertEquals(listOf("x"), a)
        assertEquals(listOf("x"), b)
    }

    @Test
    fun liveAppendsStillLandInSnapshot() {
        val store = ChannelOutputStore()
        store.attachAll("t1") { }
        store.appendAll("t1", "a", null)
        // Subscribing must not divert chunks away from the snapshot getters.
        assertEquals(listOf("a"), store.allFor("t1").map { it.text })
    }

    @Test
    fun keyForFallsBackToNameUntilLocationRemembered() {
        val store = ChannelOutputStore()
        assertEquals("stream", store.keyFor("stream"))
        store.rememberLocation("stream", "php_qn://StreamTest.php::StreamTest::stream")
        assertEquals("php_qn://StreamTest.php::StreamTest::stream", store.keyFor("stream"))
    }

    @Test
    fun clearResetsRememberedLocations() {
        val store = ChannelOutputStore()
        store.rememberLocation("stream", "loc")
        store.clear()
        assertEquals("stream", store.keyFor("stream"))
    }

    @Test
    fun descriptionIsKeyedByLocationLikeOutputStreams() {
        // rememberLocation first, then rememberDescription -> the description is found under keyFor(name), exactly the
        // key the channel UI uses to look up output for the same test.
        val store = ChannelOutputStore()
        store.rememberLocation("renders", "php_qn://WidgetTest.php::\\WidgetTest::renders")
        store.rememberDescription("renders", "Verifies the widget renders correctly.")

        assertEquals("Verifies the widget renders correctly.", store.descriptionFor(store.keyFor("renders")))
    }

    @Test
    fun descriptionFallsBackToNameWhenNoLocationRemembered() {
        val store = ChannelOutputStore()
        store.rememberDescription("renders", "desc")
        assertEquals("desc", store.descriptionFor(store.keyFor("renders")))
    }

    @Test
    fun descriptionSupportsMultiline() {
        val store = ChannelOutputStore()
        store.rememberLocation("renders", "loc")
        store.rememberDescription("renders", "line one\nline two")
        assertEquals("line one\nline two", store.descriptionFor("loc"))
    }

    @Test
    fun missingDescriptionIsNull() {
        val store = ChannelOutputStore()
        assertNull(store.descriptionFor("nope"))
    }

    @Test
    fun clearResetsDescriptions() {
        val store = ChannelOutputStore()
        store.rememberLocation("renders", "loc")
        store.rememberDescription("renders", "desc")
        store.clear()
        assertNull(store.descriptionFor("loc"))
    }

    @Test
    fun producerAndLookupKeysAgreeViaSharedKeyFor() {
        // The bug was that output was stored under the location key while the view subscribed by a name/locationUrl
        // that lagged. Both sides now derive the key from the same keyFor(name), so a subscriber finds the output.
        val store = ChannelOutputStore()
        store.rememberLocation("stream", "php_qn://StreamTest::stream")
        store.appendAll(store.keyFor("stream"), "line 1", null)

        val seen = mutableListOf<String>()
        store.attachAll(store.keyFor("stream")) { seen.add(it.text) }
        store.appendAll(store.keyFor("stream"), "line 2", null)

        assertEquals(listOf("line 1", "line 2"), seen)
    }

    @Test
    fun attachOutputReplaysExistingThenStreamsLive() {
        val store = ChannelOutputStore()
        store.appendOutput("t1", "past", null)

        val seen = mutableListOf<String>()
        store.attachOutput("t1") { seen.add(it.text) }
        assertEquals(listOf("past"), seen)

        store.appendOutput("t1", "live", null)
        assertEquals(listOf("past", "live"), seen)
    }

    @Test
    fun attachOutputDetachStopsDelivery() {
        val store = ChannelOutputStore()
        val seen = mutableListOf<String>()
        val detach = store.attachOutput("t1") { seen.add(it.text) }

        store.appendOutput("t1", "a", null)
        detach()
        store.appendOutput("t1", "b", null)

        assertEquals(listOf("a"), seen)
    }

    @Test
    fun outputAllAndChannelAreIndependentStreams() {
        val store = ChannelOutputStore()
        val out = mutableListOf<String>()
        val all = mutableListOf<String>()
        val sql = mutableListOf<String>()
        store.attachOutput("t1") { out.add(it.text) }
        store.attachAll("t1") { all.add(it.text) }
        store.attachChannel("t1", "sql") { sql.add(it.text) }

        store.appendOutput("t1", "o", null)
        store.appendAll("t1", "a", null)
        store.append("t1", "sql", "s", null)

        assertEquals(listOf("o"), out)
        assertEquals(listOf("a"), all)
        assertEquals(listOf("s"), sql)
    }

    @Test
    fun attachChannelDetachStopsDelivery() {
        val store = ChannelOutputStore()
        val seen = mutableListOf<String>()
        val detach = store.attachChannel("t1", "sql") { seen.add(it.text) }

        store.append("t1", "sql", "a", null)
        detach()
        store.append("t1", "sql", "b", null)

        assertEquals(listOf("a"), seen)
    }

    @Test
    fun clearDropsLiveSinks() {
        val store = ChannelOutputStore()
        val seen = mutableListOf<String>()
        store.attachAll("t1") { seen.add(it.text) }
        store.appendAll("t1", "before", null)

        store.clear()
        store.appendAll("t1", "after", null)

        // The subscription was attached to the pre-clear buffer, which clear() dropped; new output goes to a fresh
        // buffer with no sinks, so the stale console stops receiving.
        assertEquals(listOf("before"), seen)
    }
}
