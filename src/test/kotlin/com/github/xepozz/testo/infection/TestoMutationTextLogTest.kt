package com.github.xepozz.testo.infection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** A real `--logger-text --log-verbosity=all` report of the same run as retry.teamcity.txt. */
class TestoMutationTextLogTest {
    private val entries = TestoMutationTextLog.parse(
        Files.readString(Path.of("src/test/testData/infection/retry.mutations.log"))
    )

    @Test
    fun `every mutant of every status is there`() {
        assertEquals(19, entries.size)
    }

    @Test
    fun `a snippet starts at the file's line the context above the change begins on`() {
        // Header line 80, three context lines above the removed return: the file's lines 77-80 are the snippet's first.
        val removal = entries.getValue("7fef23dae843ecb8be780d94655a9f77")

        assertEquals(77, removal.firstLine)
        assertTrue(removal.original.startsWith("                goto run;\n"))
    }

    @Test
    fun `a timed out mutant gets its code`() {
        val decrement = entries.getValue("682c68d627a0fbe870414c3238a36ece")

        assertTrue(decrement.original, decrement.original.contains("        --\$attempts;\n"))
        assertTrue(decrement.mutated, decrement.mutated.contains("        ++\$attempts;\n"))
        assertTrue(decrement.original.contains("\$result = \$next(\$info);"))
        assertEquals("", decrement.output)
    }

    @Test
    fun `the diff ends after its trailing context, blank lines included`() {
        val flaky = entries.getValue("8ce204542deccf0c3b1e29ad6792a3a0")

        assertEquals(
            listOf(
                "    public function runTest(TestInfo \$info, callable \$next): TestResult",
                "    {",
                "        \$attempts = \$this->options->maxAttempts;",
                "        \$isFlaky = true;",
                "",
                "        # The test still counts as a single test, but the assertions and duration of the discarded",
                "        # attempts are real work — accumulate their summaries (counts stay empty at this point, so",
            ),
            flaky.mutated.lines().dropLast(1),
        )
        assertTrue(flaky.output, flaky.output.startsWith("{\n    \"status\": \"failed\","))
        assertTrue(flaky.output.contains("RetryPolicyRunInterceptorTest::noRetryWhenFirstAttemptPasses"))
    }

    @Test
    fun `nothing of the next section leaks into the last entry`() {
        entries.values.forEach { entry ->
            assertTrue(entry.output, !entry.output.contains("mutants:"))
            assertTrue(entry.mutated, !entry.mutated.contains("\"status\""))
        }
    }
}
