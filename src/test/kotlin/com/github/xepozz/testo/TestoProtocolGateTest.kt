package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoProtocolGate
import junit.framework.TestCase

class TestoProtocolGateTest : TestCase() {
    fun testNodeMessagesNeedANodeId() {
        for (name in listOf("testSuiteStarted", "testStarted", "testFinished", "testFailed", "testStdOut")) {
            assertTrue(name, TestoProtocolGate.requiresNodeId(name))
        }
    }

    fun testNonNodeMessagesAreNotGated() {
        for (name in listOf("blockOpened", "blockClosed", "testingStarted", "message")) {
            assertFalse(name, TestoProtocolGate.requiresNodeId(name))
        }
    }

    fun testMessageWithoutNodeIdIsLegacy() {
        val attrs = mapOf("name" to "OrderTest", "locationHint" to "php_qn://OrderTest.php::\\OrderTest")

        assertTrue(TestoProtocolGate.isLegacyMessage("testSuiteStarted", attrs))
        assertTrue(TestoProtocolGate.isLegacyMessage("testSuiteStarted", attrs + ("nodeId" to "")))
    }

    fun testMessageWithNodeIdIsCurrent() {
        val attrs = mapOf("name" to "OrderTest", "nodeId" to "3", "parentNodeId" to "1")

        assertFalse(TestoProtocolGate.isLegacyMessage("testSuiteStarted", attrs))
    }

    fun testNonNodeMessageIsNeverLegacy() {
        // The environment block carries no ids by design — gating on it would fire on every single run.
        assertFalse(TestoProtocolGate.isLegacyMessage("blockOpened", mapOf("name" to "Environment")))
    }

    fun testVersionIsReadOffThePlainBanner() {
        assertEquals("0.10.38", TestoProtocolGate.parseVersion("Testo v0.10.38"))
        assertEquals("1.0.0", TestoProtocolGate.parseVersion("Testo 1.0.0"))
        assertEquals("1.2.3-beta.1", TestoProtocolGate.parseVersion("Testo v1.2.3-beta.1"))
    }

    fun testVersionIsReadThroughAnsiColouring() {
        val esc = Char(27)
        val banner = "$esc[1mTesto$esc[0m$esc[2m v0.10.38$esc[0m"

        assertEquals("0.10.38", TestoProtocolGate.parseVersion(banner))
    }

    fun testOtherTextCarriesNoVersion() {
        assertNull(TestoProtocolGate.parseVersion("##teamcity[testSuiteStarted name='OrderTest']"))
        assertNull(TestoProtocolGate.parseVersion("PHP: 8.4.23 NTS"))
    }

    fun testBracketedTextSurvivesAnsiStripping() {
        // The stripper is anchored on the escape character; a suite named like this must not lose characters.
        assertNull(TestoProtocolGate.parseVersion("OrderTest [test] finished"))
    }
}
