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
    fun everyStatusHasAnIconAndALowerCaseLabel() {
        for (status in TestoTestStatus.entries) {
            assertNotNull("no icon for ${status.wireName}", status.icon)
            // "42 flaky" and "Click to show only flaky tests" both read the label as a plain noun.
            assertEquals("label for ${status.wireName} must be lower-case", status.label.lowercase(), status.label)
            assertTrue("no label for ${status.wireName}", status.label.isNotBlank())
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
    fun anOldTestoStillGetsTheThreeStatusesTeamcityCarries() {
        val store = TestoStatusStore(ChannelOutputStore())
        // No `status` anywhere: the message that closes each test is all there is to go on.
        store.noteInferred("passed", TestoTestStatus.PASSED, onlyIfAbsent = true)     // testFinished

        store.noteInferred("failed", TestoTestStatus.FAILED, onlyIfAbsent = false)    // testFailed
        store.noteInferred("failed", TestoTestStatus.PASSED, onlyIfAbsent = true)     // …then testFinished

        store.noteInferred("skipped", TestoTestStatus.SKIPPED, onlyIfAbsent = false)  // testIgnored
        store.noteInferred("skipped", TestoTestStatus.PASSED, onlyIfAbsent = true)    // …then testFinished

        assertEquals(
            mapOf(TestoTestStatus.PASSED to 1, TestoTestStatus.FAILED to 1, TestoTestStatus.SKIPPED to 1),
            store.counts(),
        )
    }

    @Test
    fun aReportedStatusOutranksAnInferredOneWhicheverArrivesFirst() {
        val early = TestoStatusStore(ChannelOutputStore())
        // testFailed with no status, then testFinished carrying status='error'.
        early.noteInferred("a", TestoTestStatus.FAILED, onlyIfAbsent = false)
        early.note("a", TestoTestStatus.ERROR)
        assertEquals(mapOf(TestoTestStatus.ERROR to 1), early.counts())

        val late = TestoStatusStore(ChannelOutputStore())
        // testFailed carrying status='aborted', then a bare testFinished that must not coarsen it back.
        late.note("a", TestoTestStatus.ABORTED)
        late.noteInferred("a", TestoTestStatus.FAILED, onlyIfAbsent = false)
        late.noteInferred("a", TestoTestStatus.PASSED, onlyIfAbsent = true)
        assertEquals(mapOf(TestoTestStatus.ABORTED to 1), late.counts())
    }

    @Test
    fun aRiskyTestIsCountedAsPassedWhenTestoCannotSayOtherwise() {
        val store = TestoStatusStore(ChannelOutputStore())
        // An old Testo announces risky only as a warning on stdout and then finishes the test normally.
        store.noteInferred("a", TestoTestStatus.PASSED, onlyIfAbsent = true)

        assertEquals(mapOf(TestoTestStatus.PASSED to 1), store.counts())
        assertEquals(1, store.finishedCount())
    }

    @Test
    fun assertionsAreSummedPerTestAndReplacedOnRestatement() {
        val store = TestoStatusStore(ChannelOutputStore())
        assertNull("no assertion attribute seen yet", store.assertionCount())

        store.noteAssertions("a", 3)
        store.noteAssertions("b", 4)
        assertEquals(7, store.assertionCount())

        // The same test reporting again replaces its own figure instead of adding to it.
        store.noteAssertions("a", 5)
        assertEquals(9, store.assertionCount())
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
    fun declaredTotalsAccumulateAcrossSuites() {
        val store = TestoStatusStore(ChannelOutputStore())
        // Testo announces one testCount per suite; the divisor is their sum, as the platform's progress bar sums them.
        store.noteDeclaredTotal(10)
        store.noteDeclaredTotal(5)

        assertEquals(15, store.totalHint())
    }

    @Test
    fun clearResetsTheTally() {
        val store = TestoStatusStore(ChannelOutputStore())
        store.note("a", TestoTestStatus.PASSED)
        store.noteAssertions("a", 2)
        store.clear()

        assertEquals(emptyMap<TestoTestStatus, Int>(), store.counts())
        assertEquals(0, store.finishedCount())
        assertEquals(0, store.totalHint())
        assertNull(store.assertionCount())
    }

    @Test
    fun countersReadAsNumberThenStatus() {
        // The shapes the toolbar renders. Digits stay ungrouped — the counts go into MessageFormat as strings.
        assertEquals("1234/2000 total", TestoBundle.message("testo.progress.total.fraction", "1234", "2000"))
        assertEquals("1234 total", TestoBundle.message("testo.progress.total", "1234"))
        assertEquals("42 flaky", TestoBundle.message("testo.progress.counter", "42", TestoTestStatus.FLAKY.label))
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
