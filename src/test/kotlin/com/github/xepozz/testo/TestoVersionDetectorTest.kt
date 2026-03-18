package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoVersionDetector
import com.intellij.execution.ExecutionException
import junit.framework.TestCase

class TestoVersionDetectorTest : TestCase() {

    fun testParse_validVersion() {
        assertEquals("1.0.0", TestoVersionDetector.parse("Testo 1.0.0"))
    }

    fun testParse_versionWithPreRelease() {
        assertEquals("2.3.1-beta.1", TestoVersionDetector.parse("Testo 2.3.1-beta.1"))
    }

    fun testParse_emptyAfterPrefix() {
        try {
            TestoVersionDetector.parse("Testo ")
            fail("Expected ExecutionException")
        } catch (_: ExecutionException) {
            // expected
        }
    }

    fun testParse_noPrefix() {
        // "substringAfter" returns original string if delimiter not found
        assertEquals("SomeOtherOutput", TestoVersionDetector.parse("SomeOtherOutput"))
    }

    fun testGetVersionOptions() {
        val options = TestoVersionDetector.getVersionOptions()
        assertEquals(2, options.size)
        assertEquals("--version", options[0])
        assertEquals("--no-ansi", options[1])
    }
}
