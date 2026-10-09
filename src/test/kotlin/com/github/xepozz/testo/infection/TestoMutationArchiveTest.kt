package com.github.xepozz.testo.infection

import com.github.xepozz.testo.runs.TestoRunStore
import com.intellij.execution.ExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TestoMutationArchiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val stream = Path.of("src/test/testData/infection/retry.teamcity.txt")
    private val textLog = Path.of("src/test/testData/infection/retry.mutations.log")

    private fun record(testoRunDir: Path, startedAt: Long): Path {
        val dir = TestoMutationArchive.newRunDir(testoRunDir, startedAt)
        val run = TestoMutationRun("retry", testoRunDir, dir) { "D:/local$it" }
        TestoMutationArchive.Recorder(dir).use { recorder ->
            recorder.line("$ php vendor/bin/infection")
            val live = TestoMutationStream(run, recorder::line)
            Files.readString(stream).chunked(50).forEach { live.feed(it, stdout = true) }
            live.flush()
            Files.copy(textLog, dir.resolve(TestoMutationArchive.TEXT_LOG))
            run.startedAt = startedAt
            run.finish(0)
            recorder.summary(run)
        }
        return dir
    }

    @Test
    fun `a recorded run reads back as it finished`() {
        val testoRun = temp.newFolder("run").toPath()
        val dir = record(testoRun, 1000)

        val restored = TestoMutationArchive.load(testoRun, TestoMutationArchive.runs(testoRun).single())

        assertNotNull(restored)
        restored!!
        assertEquals(dir, restored.workDir)
        assertFalse(restored.isRunning)
        assertEquals(0, restored.exitCode)
        assertEquals(1000, restored.startedAt)
        assertEquals(19, restored.mutants.size)
        assertEquals(94, restored.score().coveredMsi)
        assertTrue(restored.log().startsWith("$ php vendor/bin/infection\n"))
        assertTrue("every mutant gets its code", restored.mutants.all { it.original != null && it.mutated != null })
        val file = restored.files.single()
        assertEquals("D:/local${file.path}", restored.localPath(file.path))
    }

    @Test
    fun `a rerun of one mutant updates it in place when read back`() {
        val testoRun = temp.newFolder("run").toPath()
        val dir = record(testoRun, 1000)
        val name = "Infection\\Mutator\\Removal\\ReturnRemoval (7fef23dae843ecb8be780d94655a9f77)"
        TestoMutationArchive.Recorder(TestoMutationArchive.newRerunDir(dir, 2000)).use { recorder ->
            recorder.line("$ php vendor/bin/infection --id=7fef23dae843ecb8be780d94655a9f77")
            recorder.line("##teamcity[testCount count='12']")
            recorder.line("##teamcity[testStarted name='$name' nodeId='a1' parentNodeId='f1']")
            recorder.line("##teamcity[testFinished name='$name' nodeId='a1' duration='12']")
        }

        val restored = TestoMutationArchive.load(testoRun, dir)!!
        val rerun = restored.mutants.single { it.hash == "7fef23dae843ecb8be780d94655a9f77" }

        assertEquals(MutantStatus.KILLED, rerun.status)
        assertEquals(MutantStatus.ESCAPED, rerun.previousStatus)
        assertEquals(19, restored.mutants.size)
        assertEquals(0, restored.score().escaped)
        assertTrue(restored.mutants.all { it.finished })
    }

    @Test
    fun `a file's fingerprint is kept with the run and tells an edit`() {
        val testoRun = temp.newFolder("run").toPath()
        val source = temp.newFile("A.php").toPath()
        Files.writeString(source, "<?php return 1;")
        val dir = TestoMutationArchive.newRunDir(testoRun, 1000)
        val run = TestoMutationRun("a", testoRun, dir) { source.toString() }
        TestoMutationArchive.Recorder(dir).use { recorder ->
            val stream = TestoMutationStream(run, recorder::line, onFile = run::fingerprint)
            stream.feed("##teamcity[testSuiteStarted name='A.php' nodeId='f1' parentNodeId='0' locationHint='file:///app/A.php']\n", stdout = true)
            run.finish(0)
            recorder.summary(run)
        }

        val restored = TestoMutationArchive.load(testoRun, dir)!!
        val fingerprint = restored.fingerprints.getValue("/app/A.php")

        assertEquals(fingerprintOf(source), fingerprint)
        Files.writeString(source, "<?php return 2;")
        assertNotEquals(fingerprintOf(source), fingerprint)
    }

    @Test
    fun `each file is scored by the run that judged it last`() {
        fun score(escaped: Int, killed: Int, at: Long) =
            TestoMutationArchive.FileScore(mapOf("ESCAPED" to escaped, "KILLED" to killed), at)
        val full = TestoMutationArchive.Summary(
            localPaths = mapOf("/app/src/A.php" to "D:\\p\\src\\A.php", "/app/src/Sub/B.php" to "D:\\p\\src\\Sub\\B.php"),
            scores = mapOf("/app/src/A.php" to score(1, 1, 100), "/app/src/Sub/B.php" to score(2, 2, 100)),
        )
        val narrowed = TestoMutationArchive.Summary(
            localPaths = mapOf("/app/src/A.php" to "D:\\p\\src\\A.php"),
            scores = mapOf("/app/src/A.php" to score(0, 2, 200)),
        )

        val merged = mergeScores(listOf(narrowed, full))

        assertEquals(100, merged.getValue("D:/p/src/A.php").msi)
        assertEquals(50, merged.getValue("D:/p/src/Sub/B.php").msi)
        assertEquals(66, scoreUnder(merged, "D:\\p\\src", directory = true)?.msi)
        assertEquals(50, scoreUnder(merged, "D:/p/src/Sub/", directory = true)?.msi)
        assertEquals(null, scoreUnder(merged, "D:/p/src/C.php", directory = false))
    }

    @Test
    fun `a rerun restamps only the files it judged again`() {
        val testoRun = temp.newFolder("run").toPath()
        val dir = record(testoRun, 1000)
        val file = TestoMutationArchive.summary(dir)!!.scores.keys.single()
        assertEquals(1000, TestoMutationArchive.summary(dir)!!.scores.getValue(file).at)

        val run = TestoMutationArchive.load(testoRun, dir)!!
        TestoMutationArchive.writeSummary(dir, run, rescored = setOf(file))

        assertTrue(TestoMutationArchive.summary(dir)!!.scores.getValue(file).at > 1000)
        assertEquals(run.score().msi, TestoMutationArchive.scores(testoRun).values.single().msi)
    }

    @Test
    fun `the history lists a run off its summary alone`() {
        val testoRun = temp.newFolder("run").toPath()
        val dir = record(testoRun, 1000)

        val entry = TestoMutationHistoryEntry.of(dir, TestoMutationArchive.summary(dir)!!)

        assertEquals(1000, entry.startedAt)
        assertEquals(19, entry.mutants)
        assertEquals(1, entry.escaped)
        assertEquals(TestoMutationArchive.load(testoRun, dir)!!.score().msi, entry.msi)
        assertFalse(entry.running)
    }

    @Test
    fun `a stopped rerun is preserved without changing the main run outcome`() {
        val testoRun = temp.newFolder("run").toPath()
        val dir = record(testoRun, 1000)
        val run = TestoMutationArchive.load(testoRun, dir)!!
        run.rerunning = true
        run.stop()
        run.rerunning = false
        TestoMutationArchive.writeSummary(dir, run)

        val restored = TestoMutationArchive.load(testoRun, dir)!!
        assertTrue(restored.rerunStopRequested)
        assertFalse(restored.stopRequested)
        assertEquals(0, restored.exitCode)
        assertTrue(TestoMutationHistoryEntry.of(dir, TestoMutationArchive.summary(dir)!!).stopped)
    }

    @Test
    fun `an unconfirmed run blocks nothing after reload and keeps its inputs for the grace period`() {
        val source = temp.newFolder("unconfirmed").toPath()
        val dir = record(source, 1000)
        val run = TestoMutationArchive.load(source, dir)!!
        val stoppedAt = System.currentTimeMillis()
        run.restore(1000, stoppedAt, null, false, 19, failureReason = "transport lost", unconfirmedReason = "transport lost")
        TestoMutationArchive.writeSummary(dir, run)
        val restored = TestoMutationArchive.load(source, dir)!!
        assertFalse(restored.isBusy)
        assertFalse(restored.holdsProcess)
        assertEquals("transport lost", restored.failureReason)
        assertEquals(dir, TestoMutationArchive.unconfirmedRun(source))
        TestoMutationArchive.prune(source, keep = 0)
        assertTrue(Files.isDirectory(dir))

        restored.restore(1000, stoppedAt - TestoRunStore.INCOMPLETE_GRACE_MS - 1, null, false, 19,
            failureReason = "transport lost", unconfirmedReason = "transport lost")
        TestoMutationArchive.writeSummary(dir, restored)
        assertNull(TestoMutationArchive.unconfirmedRun(source))
        TestoMutationArchive.prune(source, keep = 0)
        assertFalse(Files.isDirectory(dir))
    }

    @Test
    fun `a confirmed late stop preserves the failed observation after reload`() {
        val source = temp.newFolder("confirmed").toPath()
        val dir = record(source, 1000)
        val run = TestoMutationArchive.load(source, dir)!!
        run.restore(1000, 2000, null, false, 19, failureReason = "transport lost")
        TestoMutationArchive.writeSummary(dir, run)
        val restored = TestoMutationArchive.load(source, dir)!!
        assertFalse(restored.isBusy)
        assertEquals("transport lost", restored.failureReason)
        assertNull(TestoMutationArchive.unconfirmedRun(source))
    }

    @Test
    fun `retention fails closed for a fresh run with an unreadable or missing summary`() {
        for ((index, content) in listOf("", "{", "{}", null).withIndex()) {
            val source = temp.newFolder("bad-$index").toPath()
            val dir = Files.createDirectories(source.resolve("infection/${System.currentTimeMillis()}"))
            val input = Files.writeString(Files.createDirectories(dir.resolve("coverage")).resolve("junit.xml"), "input")
            if (content != null) Files.writeString(dir.resolve(TestoMutationArchive.SUMMARY_FILE), content)
            TestoMutationArchive.prune(source, keep = 0)
            assertTrue(Files.exists(input))
            assertFalse(TestoMutationArchive.deleteRunIfSafe(source))
            assertTrue(Files.exists(input))
        }
    }

    @Test
    fun `a run without a finished summary stops pinning its Testo run after the grace period`() {
        for ((index, content) in listOf("", "{", "{}", null).withIndex()) {
            val source = temp.newFolder("stale-$index").toPath()
            val dir = Files.createDirectories(source.resolve("infection/1000"))
            Files.writeString(dir.resolve(TestoMutationArchive.STREAM_FILE), "$ php vendor/bin/infection")
            if (content != null) Files.writeString(dir.resolve(TestoMutationArchive.SUMMARY_FILE), content)
            assertTrue(TestoMutationArchive.deleteRunIfSafe(source))
            assertFalse(Files.exists(source))
        }
    }

    @Test
    fun `active inputs protect a previously successful archive until every owner releases`() {
        val source = temp.newFolder("leased").toPath()
        val dir = record(source, 1000)
        val input = Files.writeString(Files.createDirectories(dir.resolve("reruns/2000/coverage")).resolve("junit.xml"), "input")
        val first = TestoMutationArchive.protectInputs(dir.resolve("reruns/2000"))
        val second = TestoMutationArchive.protectInputs(dir.resolve("reruns/2000"))
        try {
            TestoMutationArchive.prune(source, keep = 0)
            assertFalse(TestoMutationArchive.deleteRunIfSafe(source))
            first.close()
            first.close()
            assertFalse(TestoMutationArchive.deleteRunIfSafe(source))
            assertTrue(Files.exists(input))
        } finally { first.close(); second.close() }
        assertTrue(TestoMutationArchive.deleteRunIfSafe(source))
        assertFalse(Files.exists(source))
    }

    @Test
    fun `a run claimed for deletion takes no new inputs and is deleted once`() {
        val source = temp.newFolder("claimed").toPath()
        val dir = record(source, 1000)

        assertTrue(TestoMutationArchive.claimForDeletion(source))
        assertFalse(TestoMutationArchive.claimForDeletion(source))
        assertFalse(TestoMutationArchive.deleteRunIfSafe(source))
        assertThrows(ExecutionException::class.java) { TestoMutationArchive.protectInputs(dir.resolve("reruns/2000")) }
        assertNotNull(TestoMutationArchive.summary(dir))

        TestoMutationArchive.deleteClaimed(source)
        assertFalse(Files.exists(source))
        record(source, 2000)
        TestoMutationArchive.protectInputs(source.resolve("infection/2000")).close()
        assertTrue(TestoMutationArchive.deleteRunIfSafe(source))
    }

    @Test
    fun `input protection covers preparation before the first summary exists`() {
        val source = temp.newFolder("preparing").toPath()
        val dir = source.resolve("infection/1000")
        TestoMutationArchive.protectInputs(dir).use {
            assertFalse(TestoMutationArchive.deleteRunIfSafe(source))
            assertTrue(Files.exists(source))
        }
        assertTrue(TestoMutationArchive.deleteRunIfSafe(source))
    }

    @Test
    fun `summary replacement leaves no temporary files or partially published document`() {
        val source = temp.newFolder("replace").toPath()
        val dir = record(source, 1000)
        val run = TestoMutationArchive.load(source, dir)!!
        repeat(5) {
            run.restore(1000, 2000, null, false, 19, failureReason = "transport lost", unconfirmedReason = "transport lost")
            TestoMutationArchive.writeSummary(dir, run)
            assertEquals("transport lost", TestoMutationArchive.summary(dir)!!.unconfirmedReason)
            assertEquals(0L, Files.list(dir).use { paths -> paths.filter { it.fileName.toString().endsWith(".tmp") }.count() })
        }
    }

    @Test
    fun `an archive without the rerun flag keeps its original outcome`() {
        val testoRun = temp.newFolder("run").toPath()
        val dir = record(testoRun, 1000)
        val summary = dir.resolve(TestoMutationArchive.SUMMARY_FILE)
        Files.writeString(summary, Files.readString(summary).replace("\"rerunStopped\":false,", ""))
        val restored = TestoMutationArchive.load(testoRun, dir)!!
        assertFalse(restored.rerunStopRequested)
        assertEquals(0, restored.exitCode)
    }

    @Test
    fun `pruning keeps the newest runs and a directory without a summary is not a run`() {
        val testoRun = temp.newFolder("run").toPath()
        listOf(1L, 2L, 3L).forEach { record(testoRun, it) }
        Files.createDirectories(TestoMutationArchive.newRunDir(testoRun, 4))

        assertEquals(listOf("1", "2", "3"), TestoMutationArchive.runs(testoRun).map { it.fileName.toString() })

        TestoMutationArchive.prune(testoRun, keep = 2)

        assertEquals(listOf("3"), TestoMutationArchive.runs(testoRun).map { it.fileName.toString() })
    }
}
