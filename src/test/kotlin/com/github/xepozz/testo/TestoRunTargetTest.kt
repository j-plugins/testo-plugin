package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoNodeIndex
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.github.xepozz.testo.tests.console.TestoTargetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the rerun target a service message carries: turning a location hint back into the `--filter`
 * selector Testo built it from, and telling apart the nodes that need one from the nodes that do not.
 */
class TestoRunTargetTest {

    private fun hint(tail: String) = "php_qn://D:/project/src/Internal/Calculator.php::$tail"

    @Test
    fun theSelectorIsTheTailOfTheHint() {
        // Exactly the string `--filter` takes, coordinates and all — Testo builds the hint out of it.
        assertEquals(
            "\\Testo\\Bench\\Internal\\Calculator::med:3:0",
            TestoRunTarget.filterOf(hint("\\Testo\\Bench\\Internal\\Calculator::med:3:0")),
        )
        assertEquals(
            "\\Testo\\Bench\\Internal\\Calculator::med",
            TestoRunTarget.filterOf(hint("\\Testo\\Bench\\Internal\\Calculator::med")),
        )
        assertEquals(
            "\\Testo\\Bench\\Internal\\Calculator",
            TestoRunTarget.filterOf(hint("\\Testo\\Bench\\Internal\\Calculator")),
        )
    }

    @Test
    fun aWindowsDriveLetterIsNotASeparator() {
        // The path holds a single colon; only the doubled one separates the file from the selector.
        assertEquals("\\Ns\\C::it", TestoRunTarget.filterOf("php_qn://D:/x/C.php::\\Ns\\C::it"))
    }

    @Test
    fun aHintPointingAtNothingButAFileHasNoSelector() {
        assertNull(TestoRunTarget.filterOf("php_qn://D:/project/testo.php"))
        assertNull(TestoRunTarget.filterOf(""))
    }

    @Test
    fun thePluginsOwnDisplayCoordinatesAreNotPartOfTheSelector() {
        // Line markers and history lookup append these; Testo never sends them, but a hint may arrive from either.
        assertEquals("\\Ns\\C::it", TestoRunTarget.filterOf(hint("\\Ns\\C::it") + "#2"))
        assertEquals("\\Ns\\C::it", TestoRunTarget.filterOf(hint("\\Ns\\C::it") + " with data set #3"))
    }

    @Test
    fun aSelectorOfAnyShapeCorrectsTheProducer() {
        // Whatever the hint names goes to `--filter` verbatim. A case is not exempt: the element-based path narrows it
        // to `--path <file>`, and a file may declare several cases.
        assertEquals("\\Ns\\C::it:1:2", TestoRunTarget(hint("\\Ns\\C::it:1:2")).filter)
        assertEquals("\\Ns\\C", TestoRunTarget(hint("\\Ns\\C")).filter)
        assertEquals("\\Ns\\freeFunction", TestoRunTarget(hint("\\Ns\\freeFunction")).filter)
    }

    @Test
    fun aNodeThatNarrowsNothingIsEmpty() {
        assertTrue(TestoRunTarget().isEmpty)
        assertFalse("a case hint narrows the run to that one case", TestoRunTarget(hint("\\Ns\\C")).isEmpty)
        assertFalse(TestoRunTarget(hint("\\Ns\\C::it")).isEmpty)
        assertFalse(TestoRunTarget(null, suite = "Unit").isEmpty)
        assertFalse(TestoRunTarget(null, type = "bench").isEmpty)
        assertTrue("blank attributes are as good as absent", TestoRunTarget(null, suite = " ", type = "").isEmpty)
        assertTrue("a hint naming only a file selects nothing", TestoRunTarget("php_qn://D:/p/testo.php").isEmpty)
    }

    @Test
    fun oneHintUnderTwoTypesKeepsBothTargets() {
        val store = TestoTargetStore(TestoNodeIndex())
        // A hint names code, not a node: `TestIdentity` keeps the type beside the fqn rather than in it, so a method
        // announced as an inline test and as a bench answers with the same hint twice. That is exactly the case where
        // the two nodes must not share a target — the type is what goes into `--type`.
        val shared = hint("\\Ns\\Calculator::med")
        store.note("7", TestoRunTarget(shared, type = "inline"))
        store.note("9", TestoRunTarget(shared, type = "bench"))

        assertEquals("inline", store.targetForNode("7")?.type)
        assertEquals("bench", store.targetForNode("9")?.type)
    }

    @Test
    fun aNodeWithoutAnIdIsNotWorthRemembering() {
        val store = TestoTargetStore(TestoNodeIndex())
        // Nothing to key it by, and nothing to look it up with either.
        store.note(null, TestoRunTarget(hint("\\Ns\\C"), suite = "sandbox"))

        assertNull(store.targetForNode("7"))
        assertNull(store.targetFor(null))
    }

    @Test
    fun anEmptyTargetIsNotWorthRemembering() {
        val store = TestoTargetStore(TestoNodeIndex())
        // The config-file node: its hint names a file, which selects nothing the element-based path does not already.
        store.note("7", TestoRunTarget("php_qn://D:/project/testo.php"))

        assertNull(store.targetForNode("7"))
    }

    @Test
    fun aCaseIsWorthRememberingOnItsHintAlone() {
        val store = TestoTargetStore(TestoNodeIndex())
        store.note("7", TestoRunTarget(hint("\\Ns\\TestLevelPipelineFailure")))

        assertEquals("\\Ns\\TestLevelPipelineFailure", store.targetForNode("7")?.filter)
    }
}
