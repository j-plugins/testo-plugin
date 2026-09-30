package com.github.xepozz.testo.infection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `a file and the directories above it are scored by their mutants`() {
        val testoRun = temp.newFolder("run").toPath()
        val run = TestoMutationArchive.load(testoRun, record(testoRun, 1000))!!
        val local = "D:/local${run.files.single().path}"

        assertEquals(run.score().msi, run.scoreUnder(local, directory = false)?.msi)
        assertEquals(run.score().msi, run.scoreUnder(local.substringBeforeLast('/'), directory = true)?.msi)
        assertEquals(19, run.scoreUnder("D:/local", directory = true)?.counts?.values?.sum())
        assertEquals(null, run.scoreUnder("D:/elsewhere", directory = true))
        assertEquals(null, run.scoreUnder(local.dropLast(1), directory = false))
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
    fun `pruning keeps the newest runs and a directory without a summary is not a run`() {
        val testoRun = temp.newFolder("run").toPath()
        listOf(1L, 2L, 3L).forEach { record(testoRun, it) }
        Files.createDirectories(TestoMutationArchive.newRunDir(testoRun, 4))

        assertEquals(listOf("1", "2", "3"), TestoMutationArchive.runs(testoRun).map { it.fileName.toString() })

        TestoMutationArchive.prune(testoRun, keep = 2)

        assertEquals(listOf("3"), TestoMutationArchive.runs(testoRun).map { it.fileName.toString() })
    }
}
