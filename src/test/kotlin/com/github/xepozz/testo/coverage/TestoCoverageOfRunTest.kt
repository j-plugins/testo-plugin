package com.github.xepozz.testo.coverage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TestoCoverageOfRunTest {
    private val runs = Path.of("runs").toAbsolutePath()

    @Test
    fun matchesARunDirSpelledDifferently() {
        assertTrue(isCoverageOf(listOf(runs.resolve("other/../1-a")), listOf(runs.resolve("1-a"))))
        assertTrue(isCoverageOf(listOf(runs.resolve("1-a")), listOf(Path.of("runs", "x", "..", "1-a"))))
    }

    @Test
    fun anyOfSeveralSuitesMatches() {
        assertTrue(isCoverageOf(listOf(null, runs.resolve("1-a")), listOf(runs.resolve("2-b"), runs.resolve("1-a"))))
    }

    @Test
    fun anotherRunDoesNotMatch() {
        assertFalse(isCoverageOf(listOf(runs.resolve("1-a")), listOf(runs.resolve("1-ab"), runs.resolve("1"))))
        assertFalse(isCoverageOf(listOf(runs.resolve("1-a/sub")), listOf(runs.resolve("1-a/other"))))
    }

    @Test
    fun aSuiteWithoutRunNeverMatches() {
        assertFalse(isCoverageOf(listOf(null), listOf(runs.resolve("1-a"))))
        assertFalse(isCoverageOf(emptyList(), listOf(runs.resolve("1-a"))))
    }
}
