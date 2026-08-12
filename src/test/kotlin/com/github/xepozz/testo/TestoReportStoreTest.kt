package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ReportOpenWay
import com.github.xepozz.testo.tests.console.TestoReportAutoOpen
import com.github.xepozz.testo.tests.console.TestoReportRef
import com.github.xepozz.testo.tests.console.TestoReportStore
import com.github.xepozz.testo.tests.console.isReportOf
import com.github.xepozz.testo.tests.console.reportPathCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import org.junit.Test

/**
 * Plain JUnit4 tests for the `testoReport` message and where its report is looked for — neither needs the platform.
 */
class TestoReportStoreTest {

    @Test
    fun readsEveryAttributeOfTheMessage() {
        val ref = TestoReportRef.fromAttributes(
            mapOf(
                "format" to "html",
                "path" to "D:/git/testo/testo/runtime/report/index.html",
                "relativePath" to "runtime/report/index.html",
                "name" to "Testo HTML report",
                "schemaVersion" to "1",
            )
        )

        assertEquals("html", ref!!.format)
        assertEquals("D:/git/testo/testo/runtime/report/index.html", ref.path)
        assertEquals("runtime/report/index.html", ref.relativePath)
        assertEquals("Testo HTML report", ref.name)
        assertEquals("1", ref.schemaVersion)
        assertTrue(ref.isViewable)
    }

    @Test
    fun pathIsTheOnlyRequiredAttribute() {
        assertNull(TestoReportRef.fromAttributes(mapOf("format" to "html")))
        assertNull(TestoReportRef.fromAttributes(mapOf("path" to "  ")))

        val ref = TestoReportRef.fromAttributes(mapOf("path" to "/tmp/report.html"))!!
        assertEquals("html", ref.format)
        assertNull(ref.name)
        assertNull(ref.relativePath)
    }

    @Test
    fun readsTheMessageOffARawLineOfOutput() {
        val ref = TestoReportRef.fromServiceMessageLine(
            "##teamcity[testoReport format='html' path='D:/git/testo/testo/runtime/report/index.html'" +
                " relativePath='runtime/report/index.html' name='Testo HTML report' schemaVersion='1']"
        )!!

        assertEquals("D:/git/testo/testo/runtime/report/index.html", ref.path)
        assertEquals("runtime/report/index.html", ref.relativePath)
        assertEquals("Testo HTML report", ref.name)
        assertEquals("1", ref.schemaVersion)
    }

    @Test
    fun findsTheMessageEvenBehindWhateverPrecedesIt() {
        // A colour escape or output not terminated by a newline is exactly what stops the platform parsing the line.
        val ref = TestoReportRef.fromServiceMessageLine(
            "\u001B[32mdone\u001B[0m##teamcity[testoReport path='/tmp/report/index.html']"
        )
        assertEquals("/tmp/report/index.html", ref!!.path)
    }

    @Test
    fun ignoresLinesThatAreNotThisMessage() {
        assertNull(TestoReportRef.fromServiceMessageLine("just output"))
        assertNull(TestoReportRef.fromServiceMessageLine("##teamcity[testStarted name='foo' nodeId='1']"))
        // A longer name that merely starts the same must not be read as ours.
        assertNull(TestoReportRef.fromServiceMessageLine("##teamcity[testoReportish path='/tmp/x.html']"))
        // Ours, but without the one attribute that matters.
        assertNull(TestoReportRef.fromServiceMessageLine("##teamcity[testoReport format='html']"))
    }

    @Test
    fun undoesTeamCityEscapingInValues() {
        val ref = TestoReportRef.fromServiceMessageLine(
            "##teamcity[testoReport path='/tmp/it||s/report.html' name='Line|nBreak' relativePath='a|]b']"
        )!!

        assertEquals("/tmp/it|s/report.html", ref.path)
        assertEquals("Line\nBreak", ref.name)
        assertEquals("a]b", ref.relativePath)
    }

    @Test
    fun primaryIsTheLastViewableReportAnnounced() {
        val store = TestoReportStore()
        store.note(ref("/tmp/one/index.html"))
        store.note(ref("/tmp/two/report.json", format = "json"))
        store.note(ref("/tmp/three/index.html"))

        assertEquals("/tmp/three/index.html", store.primary()!!.path)
        assertEquals(3, store.all().size)
        assertEquals(2, store.viewable().size)
    }

    @Test
    fun reportsThatAreNotPagesNeverBecomeThePrimaryOne() {
        val store = TestoReportStore()
        // Everything Testo writes is announced — a data document for external tooling, coverage, and so on — but only a
        // page is something this button can open.
        store.note(ref("/tmp/report.json", format = "json"))
        store.note(ref("/tmp/clover.xml", format = "clover"))

        assertNull(store.primary())
        assertTrue(store.viewable().isEmpty())
        assertEquals(2, store.all().size)
    }

    @Test
    fun reAnnouncedPathReplacesItsEarlierEntry() {
        val store = TestoReportStore()
        store.note(ref("/tmp/index.html", name = "first"))
        store.note(ref("/tmp/index.html", name = "second"))

        assertEquals(1, store.all().size)
        assertEquals("second", store.primary()!!.name)
    }

    @Test
    fun aRunIsUnfinishedUntilItsProcessSaysOtherwise() {
        // What keeps the button disabled: the announced path holds the previous run's report until this run ends.
        val store = TestoReportStore()
        store.note(ref("/tmp/index.html"))
        assertFalse(store.runFinished)

        store.noteRunFinished()
        assertTrue(store.runFinished)

        // A second session in the same console starts over, keeping the reports it was told about.
        store.noteRunStarted()
        assertFalse(store.runFinished)
        assertEquals(1, store.all().size)
    }

    @Test
    fun theRunStartIsFlooredToAWholeSecond() {
        // A filesystem that keeps mtime by the second would date a report written moments after the start before it.
        val store = TestoReportStore()
        store.noteRunStarted(1_700_000_123_456)

        assertEquals(1_700_000_123_000, store.runStartedAt)
    }

    @Test
    fun aFileLeftByAnEarlierRunIsNotThisRunsReport() {
        // What a stopped run leaves behind: Testo is killed before rewriting the report, so the path still holds the
        // previous one.
        val file = Files.createTempFile("testo-report", ".html")
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000))

            assertFalse(isReportOf(file, writtenAfter = 2_000))
            assertTrue(isReportOf(file, writtenAfter = 1_000))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun aMissingReportIsNoReport() {
        assertFalse(isReportOf(Path.of("no", "such", "report.html"), writtenAfter = 0))
    }

    @Test
    fun aDeferredOpenBelongsToTheRunItWasClickedIn() {
        val store = TestoReportStore()
        store.noteRunStarted(1_000)
        store.armAutoOpen("html/Report", ReportOpenWay.BROWSER, true)
        assertTrue(store.isAutoOpenArmed("html/Report", ReportOpenWay.BROWSER))
        assertFalse(store.isAutoOpenArmed("html/Other", ReportOpenWay.BROWSER))

        // The next run must not inherit a click nothing replayed — a stopped run leaves its arm behind.
        store.noteRunStarted(2_000)
        assertFalse(store.isAutoOpenArmed("html/Report", ReportOpenWay.BROWSER))
    }

    @Test
    fun theWaysOfOpeningAreIndependentFlags() {
        // Disarming the WebView must leave the browser's checkmark exactly where it was, and vice versa.
        val store = TestoReportStore()
        store.armAutoOpen("html/Report", ReportOpenWay.WEB_VIEW, true)
        store.armAutoOpen("html/Report", ReportOpenWay.BROWSER, true)

        store.armAutoOpen("html/Report", ReportOpenWay.WEB_VIEW, false)
        assertFalse(store.isAutoOpenArmed("html/Report", ReportOpenWay.WEB_VIEW))
        assertTrue(store.isAutoOpenArmed("html/Report", ReportOpenWay.BROWSER))
    }

    @Test
    fun theMuteIsOneFlagOverEveryWayAndTakesThisRunsClicksBack() {
        val store = TestoReportStore()
        store.armAutoOpen("html/Report", ReportOpenWay.WEB_VIEW, true)
        store.armAutoOpen("html/Report", ReportOpenWay.BROWSER, true)

        store.muteAutoOpen("html/Report", true)
        assertTrue(store.isAutoOpenMuted("html/Report"))
        assertFalse(store.isAutoOpenArmed("html/Report", ReportOpenWay.WEB_VIEW))
        assertFalse(store.isAutoOpenArmed("html/Report", ReportOpenWay.BROWSER))

        // Arming again is the newer word — the mute must not survive it and silently swallow the open.
        store.armAutoOpen("html/Report", ReportOpenWay.WEB_VIEW, true)
        assertFalse(store.isAutoOpenMuted("html/Report"))
    }

    @Test
    fun aMuteBelongsToTheRunItWasClickedIn() {
        // Muting silences a standing project- or application-wide choice for this run alone: the next run must
        // auto-open again without the checkmark ever having moved.
        val store = TestoReportStore()
        store.muteAutoOpen("html/Report", true)

        store.noteRunStarted(2_000)
        assertFalse(store.isAutoOpenMuted("html/Report"))
    }

    @Test
    fun theAutoOpenKeyIsTheFormatAndTheName() {
        // The report's identity across runs: the path changes with the execution environment, these do not.
        assertEquals("html/Testo HTML report", TestoReportAutoOpen.keyOf(ref("/tmp/x.html", name = "Testo HTML report")))
        assertEquals("html/", TestoReportAutoOpen.keyOf(ref("/tmp/x.html")))
    }

    @Test
    fun clearForgetsThePreviousRun() {
        val store = TestoReportStore()
        store.note(ref("/tmp/index.html"))
        store.clear()

        assertNull(store.primary())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun mappedPathIsTriedFirstThenTheRawOneThenTheProjectRelativeForm() {
        // The mapper's answer must not spell the project-relative form: on an OS whose separator matches the
        // announcement's, the two candidates would be one string and the dedup would fold them.
        val candidates = reportPathCandidates(
            ref("/app/runtime/report/index.html", relativePath = "runtime/report/index.html"),
            projectBasePath = "/home/me/project",
        ) { "/home/me/mapped/runtime/report/index.html" }

        assertEquals(
            listOf(
                "/home/me/mapped/runtime/report/index.html",
                "/app/runtime/report/index.html",
                Path.of("/home/me/project", "runtime/report/index.html").toString(),
            ),
            candidates,
        )
    }

    @Test
    fun localRunCollapsesToASingleCandidate() {
        // The mapper answers with the path itself and the relative form resolves to the same file.
        val path = Path.of("/home/me/project", "runtime/report/index.html").toString()
        val candidates = reportPathCandidates(
            ref(path, relativePath = "runtime/report/index.html"),
            projectBasePath = "/home/me/project",
        ) { it }

        assertEquals(listOf(path), candidates)
    }

    @Test
    fun unmappedPathWithoutRelativeFormLeavesOnlyItself() {
        val candidates = reportPathCandidates(
            ref("/app/report/index.html"),
            projectBasePath = null,
        ) { null }

        assertEquals(listOf("/app/report/index.html"), candidates)
    }

    private fun ref(
        path: String,
        format: String = "html",
        relativePath: String? = null,
        name: String? = null,
    ) = TestoReportRef(format, path, relativePath, name, "1")
}
