package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.TestoTestStatus
import com.github.xepozz.testo.tests.console.hiddenByToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the toolbar's two standing toggles hide, expressed in Testo's statuses instead of the three the TeamCity
 * protocol can carry — where flaky and risky both arrive as plain passed, and no toggle could ever tell them apart.
 */
class TestoStandingFilterTest {

    @Test
    fun neitherToggleHidesAnything() {
        assertTrue(hiddenByToggles(hidePassed = false, hideIgnored = false).isEmpty())
    }

    @Test
    fun hidingPassedTakesFlakyWithIt() {
        // Same check mark, only yellow: a test that came out green on the retry is not what a failures-only view is for.
        assertEquals(
            setOf(TestoTestStatus.PASSED, TestoTestStatus.FLAKY),
            hiddenByToggles(hidePassed = true, hideIgnored = false),
        )
    }

    @Test
    fun hidingIgnoredTakesCancelledWithIt() {
        // Neither reached a verdict, and Testo reports both to the platform through `testIgnored`.
        assertEquals(
            setOf(TestoTestStatus.SKIPPED, TestoTestStatus.CANCELLED),
            hiddenByToggles(hidePassed = false, hideIgnored = true),
        )
    }

    @Test
    fun withBothTogglesOffTheTreeHoldsWhatIsWorthLookingAt() {
        val hidden = hiddenByToggles(hidePassed = true, hideIgnored = true)
        val left = TestoTestStatus.entries.filter { it !in hidden }

        // Risky survives on purpose: the framework could not vouch for that test, which is precisely the case a
        // failures-only view exists to surface. Before this it was hidden as "passed", unreachable by any toggle.
        assertEquals(
            listOf(
                TestoTestStatus.FAILED,
                TestoTestStatus.ERROR,
                TestoTestStatus.RISKY,
                TestoTestStatus.ABORTED,
            ),
            left,
        )
    }
}
