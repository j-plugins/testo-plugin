package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoTestRunConfigurationEditor
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic guard on the editor's static list of selectable coverage engines (no Swing / no fixture is touched —
 * SUPPORTED_COVERAGE_ENGINES is a plain List on the companion). Locks the supported set and its ordering so a UI tweak
 * cannot silently drop or reorder an engine.
 */
class TestoTestRunConfigurationEditorCompanionTest {

    @Test
    fun supportedEnginesAreXdebugThenPcovInOrder() {
        assertEquals(
            listOf(CoverageEngine.XDEBUG, CoverageEngine.PCOV),
            TestoTestRunConfigurationEditor.SUPPORTED_COVERAGE_ENGINES,
        )
    }

    @Test
    fun supportedEnginesHaveNoDuplicates() {
        val engines = TestoTestRunConfigurationEditor.SUPPORTED_COVERAGE_ENGINES
        assertEquals(engines.size, engines.toSet().size)
    }

    @Test
    fun defaultRunnerCoverageEngineIsOffered() {
        // The applyEditorTo fallback and TestoRunnerSettings default are both XDEBUG; it must be selectable.
        assertTrue(TestoTestRunConfigurationEditor.SUPPORTED_COVERAGE_ENGINES.contains(CoverageEngine.XDEBUG))
    }
}
