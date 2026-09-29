package com.github.xepozz.testo.infection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `pruning keeps the newest runs and a directory without a summary is not a run`() {
        val testoRun = temp.newFolder("run").toPath()
        listOf(1L, 2L, 3L).forEach { record(testoRun, it) }
        Files.createDirectories(TestoMutationArchive.newRunDir(testoRun, 4))

        assertEquals(listOf("1", "2", "3"), TestoMutationArchive.runs(testoRun).map { it.fileName.toString() })

        TestoMutationArchive.prune(testoRun, keep = 2)

        assertEquals(listOf("3"), TestoMutationArchive.runs(testoRun).map { it.fileName.toString() })
    }
}
