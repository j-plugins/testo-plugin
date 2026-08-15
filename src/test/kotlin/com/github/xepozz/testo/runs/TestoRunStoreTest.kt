package com.github.xepozz.testo.runs

import com.github.xepozz.testo.tests.console.TestoRunTimings
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Pure tests for the run-archive pieces that need no IDE: the JSONL output framing survives a round trip (including
 * teamcity messages, newlines and quotes), and the manifest serde tolerates junk. The store itself is a project
 * service; its read half is exercised here through the same Gson framing the recording writes.
 */
class TestoRunStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val gson = Gson()

    @Test
    fun outputFramingRoundTripsChunksInOrder() {
        val dir = temp.newFolder("run").toPath()
        val recording = TestoRunRecording(dir, "cfg", "Run", 123L)
        val chunks = listOf(
            TestoRunRecording.STDOUT to "##teamcity[testStarted name='a' nodeId='1' parentNodeId='0']\n",
            TestoRunRecording.STDERR to "PHP Warning: 'quoted' and\nmultiline\n",
            TestoRunRecording.SYSTEM to "process exited",
            TestoRunRecording.STDOUT to "plain tail with unicode — ✓\n",
        )
        chunks.forEach { (s, t) -> recording.appendChunk(s, t) }
        recording.closeOutput()

        val replayed = ArrayList<Pair<Int, String>>()
        Files.newBufferedReader(dir.resolve(TestoRunRecording.OUTPUT_FILE), StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val chunk = gson.fromJson(line, TestoRunRecording.Chunk::class.java)
                replayed += chunk.s to chunk.t
            }
        }
        assertEquals(chunks, replayed)
    }

    @Test
    fun appendAfterCloseIsIgnored() {
        val dir = temp.newFolder("closed").toPath()
        val recording = TestoRunRecording(dir, "cfg", "Run", 1L)
        recording.appendChunk(TestoRunRecording.STDOUT, "kept")
        recording.closeOutput()
        recording.appendChunk(TestoRunRecording.STDOUT, "dropped")

        val lines = Files.readAllLines(dir.resolve(TestoRunRecording.OUTPUT_FILE))
        assertEquals(1, lines.size)
    }

    @Test
    fun locationsAreNormalizedDedupedAndWrittenOnePerLine() {
        val dir = temp.newFolder("locations").toPath()
        val recording = TestoRunRecording(dir, "cfg", "Run", 1L)
        listOf(
            "php_qn://D:/app/OrderTest.php::\\App\\OrderTest::testPay",
            "php_qn://D:/app/OrderTest.php::\\App\\OrderTest::testPay#2",
            "php_qn://D:/app/OrderTest.php::\\App\\OrderTest::testPay with data set #3",
            "php_qn://D:/app/OrderTest.php::\\App\\OrderTest",
        ).forEach { recording.noteLocation(it) }
        recording.writeLocations()

        assertEquals(
            listOf(
                "php_qn://D:/app/OrderTest.php::\\App\\OrderTest::testPay",
                "php_qn://D:/app/OrderTest.php::\\App\\OrderTest",
            ),
            Files.readAllLines(dir.resolve(TestoRunRecording.TESTS_FILE)),
        )
    }

    @Test
    fun manifestRoundTripsThroughJson() {
        val manifest = TestoRunManifest(
            configurationName = "All tests",
            executorId = "Coverage",
            commandLine = "php bin/testo run -q -n --teamcity",
            configuration = "<configuration name=\"All tests\" />",
            startedAt = 1000,
            finishedAt = 2000,
            timings = TestoRunTimings.Marks(1010, 1100, 1900, 1990),
            statuses = mapOf("passed" to 103, "failed" to 42),
            reports = listOf(
                StoredReport("cobertura", "Cobertura coverage", "/app/r.xml", "r.xml", "reports/cobertura.xml"),
                StoredReport("html", "HTML report", "/app/report", null, null),
            ),
        )
        val parsed = gson.fromJson(gson.toJson(manifest), TestoRunManifest::class.java)
        assertEquals(manifest, parsed)
    }

    @Test
    fun malformedManifestParsesToNullNotThrow() {
        assertNull(runCatching { gson.fromJson("{not json", TestoRunManifest::class.java) }.getOrNull())
    }

    @Test
    fun finishGuardAdmitsExactlyOneFinalizer() {
        val recording = TestoRunRecording(temp.newFolder("g").toPath(), "cfg", "Run", 1L)
        assertEquals(true, recording.tryBeginFinish())
        assertEquals(false, recording.tryBeginFinish())
    }
}
