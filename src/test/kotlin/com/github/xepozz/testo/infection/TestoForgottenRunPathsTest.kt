package com.github.xepozz.testo.infection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** What `TestoMutationService.forget` drops: a deleted run's own entries and those keyed by a file in its archive. */
class TestoForgottenRunPathsTest {
    private val run = Path.of("runs", "1-a").toAbsolutePath().normalize()

    @Test
    fun theRunItself() {
        assertTrue(isUnder(Path.of("runs", "x", "..", "1-a"), listOf(run)))
    }

    @Test
    fun theCoverageXmlInsideItsArchive() {
        assertTrue(isUnder(run.resolve("reports/coverage-xml/index.xml"), listOf(run)))
    }

    @Test
    fun notASiblingSharingItsNamePrefix() {
        assertFalse(isUnder(Path.of("runs", "1-ab", "reports").toAbsolutePath(), listOf(run)))
        assertFalse(isUnder(Path.of("runs").toAbsolutePath(), listOf(run)))
    }
}
