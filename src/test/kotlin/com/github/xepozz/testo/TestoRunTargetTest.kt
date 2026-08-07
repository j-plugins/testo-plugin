package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ChannelOutputStore
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
    fun onlyASelectorNamingAMethodCorrectsTheProducer() {
        // A case or a free function is already what the resolved PSI produces; a method selector is not.
        assertEquals(
            "\\Ns\\C::it:1:2",
            TestoRunTarget(hint("\\Ns\\C::it:1:2")).methodFilter,
        )
        assertNull(TestoRunTarget(hint("\\Ns\\C")).methodFilter)
        assertNull(TestoRunTarget(hint("\\Ns\\freeFunction")).methodFilter)
    }

    @Test
    fun aNodeThatNarrowsNothingIsEmpty() {
        assertTrue(TestoRunTarget().isEmpty)
        assertTrue("a case hint alone adds nothing", TestoRunTarget(hint("\\Ns\\C")).isEmpty)
        assertFalse(TestoRunTarget(hint("\\Ns\\C::it")).isEmpty)
        assertFalse(TestoRunTarget(hint("\\Ns\\C"), suite = "Unit").isEmpty)
        assertFalse(TestoRunTarget(hint("\\Ns\\C"), type = "bench").isEmpty)
        assertTrue("blank attributes are as good as absent", TestoRunTarget(null, suite = " ", type = "").isEmpty)
    }

    @Test
    fun targetsAreKeyedByLocationHintLikeChannelOutput() {
        val channels = ChannelOutputStore()
        val store = TestoTargetStore(channels)

        // Two nodes of the same name in different classes: the hint recorded on testStarted tells them apart.
        val first = hint("\\Ns\\A::it")
        channels.rememberLocation("it", first)
        store.note("it", TestoRunTarget(first))

        val second = hint("\\Ns\\B::it")
        channels.rememberLocation("it", second)
        store.note("it", TestoRunTarget(second))

        assertEquals("\\Ns\\B::it", store.targetFor("it")?.methodFilter)
    }

    @Test
    fun anEmptyTargetIsNotWorthRemembering() {
        val store = TestoTargetStore(ChannelOutputStore())
        store.note("Unit", TestoRunTarget())

        assertNull(store.targetFor("Unit"))
    }
}
