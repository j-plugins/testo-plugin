package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ChannelOutputStore
import com.github.xepozz.testo.tests.console.TestoProgressAction
import com.github.xepozz.testo.tests.console.TestoStatusStore
import com.github.xepozz.testo.tests.console.TestoTestStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the toolbar summary: the wire contract with Testo's `status` attribute, the tally the widget
 * renders, and the elapsed-time formatting. Nothing here needs the IDE platform.
 */
class TestoTestStatusTest {

    @Test
    fun everyPhpStatusCaseIsCovered() {
        // The cases of Testo\Core\Value\Status, lower-cased. A new case there must land here too, or its tests are
        // silently dropped from the counters.
        val phpCases = listOf("passed", "failed", "skipped", "error", "risky", "flaky", "cancelled", "aborted")
        assertEquals(phpCases.sorted(), TestoTestStatus.entries.map { it.wireName }.sorted())
    }

    @Test
    fun wireNamesResolveIgnoringCaseAndPadding() {
        assertEquals(TestoTestStatus.RISKY, TestoTestStatus.fromWire("Risky"))
        assertEquals(TestoTestStatus.ABORTED, TestoTestStatus.fromWire("  ABORTED "))
        assertNull(TestoTestStatus.fromWire(null))
        assertNull(TestoTestStatus.fromWire(""))
        assertNull(TestoTestStatus.fromWire("whatever"))
    }

    @Test
    fun everyStatusHasAnIconAndALabel() {
        for (status in TestoTestStatus.entries) {
            assertNotNull("no icon for ${status.wireName}", status.icon)
            assertTrue("no label for ${status.wireName}", status.displayName.isNotBlank())
        }
    }

    @Test
    fun onlyFailureLikeStatusesMakeTheRunRed() {
        assertEquals(
            listOf(TestoTestStatus.FAILED, TestoTestStatus.ERROR, TestoTestStatus.ABORTED),
            TestoTestStatus.entries.filter { it.isProblem },
        )
    }

    @Test
    fun tallyCountsOneEntryPerTest() {
        val store = TestoStatusStore(ChannelOutputStore())
        store.note("a", TestoTestStatus.PASSED)
        store.note("b", TestoTestStatus.PASSED)
        store.note("c", TestoTestStatus.RISKY)

        assertEquals(mapOf(TestoTestStatus.PASSED to 2, TestoTestStatus.RISKY to 1), store.counts())
        assertEquals(3, store.finishedCount())
    }

    @Test
    fun restatingATestMovesItBetweenCounters() {
        val store = TestoStatusStore(ChannelOutputStore())
        // A test reports failed first and then finishes as flaky after a retry; it must not be counted twice.
        store.note("a", TestoTestStatus.FAILED)
        store.note("a", TestoTestStatus.FLAKY)

        assertEquals(mapOf(TestoTestStatus.FLAKY to 1), store.counts())
        assertEquals(1, store.finishedCount())
    }

    @Test
    fun statusesAreKeyedByLocationHintLikeChannelOutput() {
        val channels = ChannelOutputStore()
        // Two tests of the same name in different classes: the hint recorded on testStarted is what tells them apart.
        channels.rememberLocation("itWorks", "php_qn:///a.php::\\A::itWorks")
        val store = TestoStatusStore(channels)
        store.note("itWorks", TestoTestStatus.PASSED)

        channels.rememberLocation("itWorks", "php_qn:///b.php::\\B::itWorks")
        store.note("itWorks", TestoTestStatus.FAILED)

        assertEquals(mapOf(TestoTestStatus.PASSED to 1, TestoTestStatus.FAILED to 1), store.counts())
    }

    @Test
    fun totalFallsBackToStartedTestsUntilTestoAnnouncesOne() {
        val store = TestoStatusStore(ChannelOutputStore())
        assertEquals(0, store.totalHint())

        repeat(3) { store.noteStarted() }
        assertEquals(3, store.totalHint())

        store.noteDeclaredTotal(10)
        assertEquals(10, store.totalHint())
    }

    @Test
    fun clearResetsTheTally() {
        val store = TestoStatusStore(ChannelOutputStore())
        store.note("a", TestoTestStatus.PASSED)
        store.clear()

        assertEquals(emptyMap<TestoTestStatus, Int>(), store.counts())
        assertEquals(0, store.finishedCount())
        assertEquals(0, store.totalHint())
    }

    @Test
    fun elapsedTimeIsSecondsUntilAMinute() {
        assertEquals("0.25 sec", TestoProgressAction.formatElapsed(250))
        assertEquals("59.46 sec", TestoProgressAction.formatElapsed(59_456))
        // Past a minute the hundredths are noise; whole seconds under a minute counter read better.
        assertEquals("1 min 03 sec", TestoProgressAction.formatElapsed(63_400))
        assertEquals("60 min 00 sec", TestoProgressAction.formatElapsed(3_600_000))
    }
}
