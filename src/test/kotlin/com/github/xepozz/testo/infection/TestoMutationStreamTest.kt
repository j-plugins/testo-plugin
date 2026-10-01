package com.github.xepozz.testo.infection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** A real `infection --teamcity` stream (Testo's retry plugin), fed in chunks that cut lines apart. */
class TestoMutationStreamTest {

    private fun replay(chunk: Int): TestoMutationRun {
        val run = TestoMutationRun("retry", Path.of("run"), Path.of("work")) { it }
        val stream = TestoMutationStream(run)
        val text = Files.readString(Path.of("src/test/testData/infection/retry.teamcity.txt"))
        text.chunked(chunk).forEach { stream.feed(it, stdout = true) }
        stream.flush()
        run.finish(0)
        return run
    }

    @Test
    fun `mutants land under their file with their result`() {
        val run = replay(chunk = 37)

        val file = run.files.single()
        assertEquals("plugin/retry/src/Interceptor/RetryPolicyRunInterceptor.php", file.name)
        assertEquals("/app/plugin/retry/src/Interceptor/RetryPolicyRunInterceptor.php", file.path)
        assertEquals(19, file.mutants.size)
        assertTrue(file.mutants.all { it.finished })
        assertEquals(
            mapOf(MutantStatus.KILLED to 16, MutantStatus.TIMED_OUT to 2, MutantStatus.ESCAPED to 1),
            run.score().counts,
        )
        // What Infection itself printed at the end of this stream: "Covered Code MSI: 94%".
        assertEquals(94, run.score().coveredMsi)

        val killed = file.mutants.first { it.mutator == "FalseValue" }
        assertEquals("Infection\\Mutator\\Boolean\\FalseValue", killed.mutatorClass)
        assertEquals("8ce204542deccf0c3b1e29ad6792a3a0", killed.hash)
        assertEquals(1335, killed.start)
        assertEquals(1339, killed.end)
    }

    @Test
    fun `an escaped mutant keeps the original and the mutated code`() {
        val escaped = replay(chunk = 4096).mutants.single { it.status == MutantStatus.ESCAPED }

        assertEquals("ReturnRemoval", escaped.mutator)
        assertTrue(escaped.original!!.contains("return \$result->withSummary"))
        assertNotNull(escaped.mutated)
        assertTrue(!escaped.mutated!!.contains("return \$result->withSummary"))
    }

    @Test
    fun `plain output is kept as the log`() {
        assertTrue(replay(chunk = 100).log().contains("19 mutations were generated"))
    }

    @Test
    fun `score follows Infection's metrics`() {
        val score = MutationScore(
            mapOf(
                MutantStatus.KILLED to 6,
                MutantStatus.TIMED_OUT to 1,
                MutantStatus.ESCAPED to 1,
                MutantStatus.NOT_COVERED to 2,
                MutantStatus.SKIPPED to 5,
            )
        )

        assertEquals(70, score.msi)
        assertEquals(87, score.coveredMsi)
        assertEquals(null, MutationScore(emptyMap()).msi)
    }

    @Test
    fun `status comes off the result line`() {
        assertEquals(
            MutantStatus.NOT_COVERED,
            MutantStatus.fromMessage("Mutator: X\nMutation ID: y\nMutation result: not covered"),
        )
        assertEquals(null, MutantStatus.fromMessage("Mutator: X"))
    }
}
