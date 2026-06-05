package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.testoDisplayName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit4 tests for [testoDisplayName] — the per-test label used in aggregated channel output. Pure string logic,
 * no IDE platform. Covers standalone functions, class methods with full and root namespaces, and data-provider datasets
 * (which must keep their ordering label and not double the method name).
 *
 * The location formats mirror what Testo emits (see TestoTestRunLineMarkerProvider.getLocationHint):
 *   php_qn://<file>::\<Fqn>::<method>[ with data set #N]
 * and the presentable names mirror the SM test tree node text (e.g. "Dataset #0:0 [0]").
 */
class TestoDisplayNameTest {

    @Test
    fun standaloneFunctionInRootNamespaceIsNotDoubled() {
        // Regression: this used to render as "\viaDivision:viaDivision".
        assertEquals(
            "\\viaDivision",
            testoDisplayName("php_qn://path/to/functions.php::\\viaDivision", "viaDivision"),
        )
    }

    @Test
    fun standaloneFunctionInNamespaceKeepsItsFqn() {
        assertEquals(
            "\\App\\Helpers\\sum",
            testoDisplayName("php_qn://path/to/helpers.php::\\App\\Helpers\\sum", "sum"),
        )
    }

    @Test
    fun classMethodWithFullNamespace() {
        assertEquals(
            "\\Tests\\Bench\\Self\\BenchAttr::sumLinearF1",
            testoDisplayName(
                "php_qn://path/to/BenchAttr.php::\\Tests\\Bench\\Self\\BenchAttr::sumLinearF1",
                "sumLinearF1",
            ),
        )
    }

    @Test
    fun classMethodWithRootNamespace() {
        assertEquals(
            "\\BenchAttr::sumLinearF1",
            testoDisplayName("php_qn://path/to/BenchAttr.php::\\BenchAttr::sumLinearF1", "sumLinearF1"),
        )
    }

    @Test
    fun classMethodWithoutLeadingBackslash() {
        assertEquals(
            "Foo::bar",
            testoDisplayName("php_qn://path/to/Foo.php::Foo::bar", "bar"),
        )
    }

    @Test
    fun datasetDropsWithDataSetSuffixAndUsesItsPresentableName() {
        assertEquals(
            "\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #0:0",
            testoDisplayName(
                "php_qn://path/to/AssertTest.php::\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #0",
                "Dataset #0:0 [0]",
            ),
        )
    }

    @Test
    fun datasetsKeepTheirOrderingLabelsInArrivalOrder() {
        val base = "php_qn://path/to/AssertTest.php::\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #"
        val rows = listOf("Dataset #0:0 [0]", "Dataset #0:1 [1]", "Dataset #1:0 [0]", "Dataset #2:0 [name]")
        val displayed = rows.mapIndexed { i, name -> testoDisplayName("$base$i", name) }
        assertEquals(
            listOf(
                "\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #0:0",
                "\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #0:1",
                "\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #1:0",
                "\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #2:0",
            ),
            displayed,
        )
        // Every dataset gets a distinct label (no collapsing / reordering).
        assertEquals(displayed.size, displayed.toSet().size)
    }

    @Test
    fun datasetWithBracketedValueContainingDigitsIsPreserved() {
        assertEquals(
            "\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #0:8",
            testoDisplayName(
                "php_qn://path/to/AssertTest.php::\\Tests\\Sandbox\\Self\\AssertTest::dataProvider with data set #8",
                "Dataset #0:8 [Any warrior can change the world.]",
            ),
        )
    }

    @Test
    fun missingLocationFallsBackToPresentableName() {
        assertEquals("repeatFail", testoDisplayName(null, "repeatFail"))
    }

    @Test
    fun fileOnlyLocationFallsBackToPresentableName() {
        assertEquals("repeatFail", testoDisplayName("php_qn://path/to/AssertTest.php", "repeatFail"))
    }

    @Test
    fun blankFqnFallsBackToPresentableName() {
        assertTrue(testoDisplayName("php_qn://path/to/AssertTest.php::", "x") == "x")
    }
}
