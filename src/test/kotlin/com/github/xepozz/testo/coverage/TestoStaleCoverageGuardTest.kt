package com.github.xepozz.testo.coverage

import com.intellij.diagnostic.PluginException
import com.intellij.openapi.extensions.PluginId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.InvalidPathException

/** Pure detection of the stale-coverage load failure — no platform fixture. */
class TestoStaleCoverageGuardTest {

    @Test
    fun detectsInvalidPathExceptionInCauseChain() {
        val root = RuntimeException("wrapper", IllegalStateException("mid", InvalidPathException("C:\\a\\D:\\b", "Illegal char")))
        assertTrue(TestoStaleCoverageGuard.isStaleCoverageFailure(root))
    }

    @Test
    fun detectsCoverageComponentInMessage() {
        val e = PluginException(
            "Cannot init component state (componentName=com.intellij.coverage.CoverageDataManagerImpl, componentClass=CoverageDataSuitesManager)",
            PluginId.getId("com.intellij"),
        )
        assertTrue(TestoStaleCoverageGuard.isStaleCoverageFailure(e))
    }

    @Test
    fun ignoresUnrelatedFailures() {
        assertFalse(TestoStaleCoverageGuard.isStaleCoverageFailure(IOException("disk full")))
        assertFalse(TestoStaleCoverageGuard.isStaleCoverageFailure(RuntimeException("something else")))
    }
}
