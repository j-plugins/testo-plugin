package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.testoDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edge-case companion to [TestoDisplayNameTest] for [testoDisplayName]. Pure string logic, no IDE platform.
 * Targets boundaries the existing suite does not exercise: the interaction between " with data set" stripping and
 * the presentableName == method short-circuit, multi-"::" locations, and empty/whitespace presentable names.
 */
class TestoDisplayNameEdgeCasesTest {

    @Test
    fun datasetWhosePresentableNameEqualsTheBareMethodIsNotSuffixed() {
        // After stripping " with data set #0" the fqn ends in ::dataProvider; if the presentable name happens to be the
        // bare method, the short-circuit keeps just the fqn (no ":dataProvider" doubling).
        assertEquals(
            "\\Ns\\C::dataProvider",
            testoDisplayName(
                "php_qn://path/C.php::\\Ns\\C::dataProvider with data set #0",
                "dataProvider",
            ),
        )
    }

    @Test
    fun extraDoubleColonSegmentsKeepEverythingAfterFirst() {
        // substringAfter("::") only splits once, so a value containing "::" is preserved in the fqn.
        assertEquals(
            "\\Ns\\C::method::weird:label",
            testoDisplayName("php_qn://path/C.php::\\Ns\\C::method::weird", "label"),
        )
    }

    @Test
    fun standaloneFunctionWithoutLeadingBackslashIsNotDoubled() {
        assertEquals(
            "helper",
            testoDisplayName("php_qn://path/h.php::helper", "helper"),
        )
    }

    @Test
    fun emptyPresentableNameWithResolvableFqnIsAppended() {
        // presentableName != method ("" != "m"), so it is appended verbatim after a colon.
        assertEquals(
            "\\Ns\\C::m:",
            testoDisplayName("php_qn://path/C.php::\\Ns\\C::m", ""),
        )
    }

    @Test
    fun locationWithoutSchemeSeparatorFallsBackToPresentableName() {
        // No "://" -> substringAfter("://", "") yields "", then blank -> fallback.
        assertEquals("presentable", testoDisplayName("no-scheme::\\Ns\\C::m", "presentable"))
    }

    @Test
    fun datasetSuffixOnRootNamespaceMethodKeepsLabel() {
        assertEquals(
            "\\C::m:Dataset #1:2 [x]",
            testoDisplayName("php_qn://path/C.php::\\C::m with data set #3", "Dataset #1:2 [x]"),
        )
    }
}
