package com.github.xepozz.testo.runs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText

/** Pure tests for exporting a run directory as one file and reading it back — no IDE, just the archive layout. */
class TestoRunArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aRunSurvivesTheRoundTripWithItsReports() {
        val run = temp.newFolder("run").toPath()
        Files.writeString(run.resolve(TestoRunRecording.MANIFEST_FILE), """{"v":3,"configurationName":"All tests"}""")
        Files.writeString(run.resolve(TestoRunRecording.OUTPUT_FILE), "{\"s\":1,\"t\":\"##teamcity[x]\\n\"}\n")
        Files.createDirectories(run.resolve("reports/coverage-xml"))
        Files.writeString(run.resolve("reports/cobertura.xml"), "<coverage/>")
        Files.writeString(run.resolve("reports/coverage-xml/index.xml"), "<phpunit/>")

        val zip = temp.newFolder("out").toPath().resolve("export.zip")
        zipRunDirectory(run, zip)
        val restored = temp.newFolder("restored").toPath()
        unzipRunDirectory(zip, restored)

        assertEquals(
            """{"v":3,"configurationName":"All tests"}""",
            restored.resolve(TestoRunRecording.MANIFEST_FILE).readText(),
        )
        assertEquals("<coverage/>", restored.resolve("reports/cobertura.xml").readText())
        assertEquals("<phpunit/>", restored.resolve("reports/coverage-xml/index.xml").readText())
    }

    @Test
    fun entriesPointingOutsideTheTargetAreSkipped() {
        val zip = temp.newFolder("evil").toPath().resolve("evil.zip")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            out.putNextEntry(ZipEntry("../escaped.txt"))
            out.write("nope".toByteArray(StandardCharsets.UTF_8))
            out.closeEntry()
            out.putNextEntry(ZipEntry(TestoRunRecording.MANIFEST_FILE))
            out.write("{}".toByteArray(StandardCharsets.UTF_8))
            out.closeEntry()
        }
        Files.createDirectories(zip.parent)
        Files.write(zip, bytes.toByteArray())

        val target = temp.newFolder("target").toPath().resolve("run")
        unzipRunDirectory(zip, target)

        assertTrue(Files.exists(target.resolve(TestoRunRecording.MANIFEST_FILE)))
        assertFalse(Files.exists(target.parent.resolve("escaped.txt")))
    }

    @Test
    fun anIndexEntryMeansTheWholeDirectoryIsTheReport() {
        // Both of Testo's report layouts announce their entry file; `index.` is what tells a directory report apart.
        assertTrue(isDirectoryReport(Path.of("/app/var/report/index.html")))
        assertTrue(isDirectoryReport(Path.of("/app/var/coverage-xml/index.xml")))
        assertFalse(isDirectoryReport(Path.of("/app/var/report.html")))
        assertFalse(isDirectoryReport(Path.of("/app/var/clover.xml")))
    }

    @Test
    fun aCapturedReportIsNamedAfterItsFormat() {
        assertEquals("html", capturedReportName("html", Path.of("/app/var/report/index.html")))
        assertEquals("html.html", capturedReportName("html", Path.of("/app/var/report.html")))
        assertEquals("cobertura.xml", capturedReportName("cobertura", Path.of("/app/var/cobertura.xml")))
        assertEquals("junit", capturedReportName("junit", Path.of("/app/var/junit")))
    }

    @Test
    fun theExportedNameIsReadableAndFileSystemSafe() {
        val manifest = TestoRunManifest(configurationName = "All tests: unit / db", startedAt = 1700000000000)
        assertEquals("All-tests-unit-db-1700000000000", exportFileName(manifest))
    }

    @Test
    fun anUnnamedRunStillGetsAName() {
        assertEquals("testo-run-42", exportFileName(TestoRunManifest(startedAt = 42)))
    }
}
