package com.github.xepozz.testo

import com.github.xepozz.testo.tests.actions.TestoRerunFailedTestsAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit4 tests for [TestoRerunFailedTestsAction.locationUrlToFilter] — pure string logic, no IDE platform.
 */
class TestoRerunFailedTestsActionTest {

    private fun convert(locationUrl: String) =
        TestoRerunFailedTestsAction.locationUrlToFilter(locationUrl)

    @Test
    fun methodSelectorIsExtracted() {
        assertEquals(
            "\\Tests\\Sandbox\\Self\\AssertTest::repeatFail",
            convert("php_qn://path/to/AssertTest.php::\\Tests\\Sandbox\\Self\\AssertTest::repeatFail")
        )
    }

    @Test
    fun dataSetSuffixCollapsesToMethod() {
        assertEquals(
            "\\Tests\\Sandbox\\Self\\AssertTest::repeatFail",
            convert("php_qn://path/to/AssertTest.php::\\Tests\\Sandbox\\Self\\AssertTest::repeatFail with data set #3")
        )
        assertEquals(
            "\\Tests\\Sandbox\\Self\\AssertTest::repeatFail",
            convert("php_qn://path/to/AssertTest.php::\\Tests\\Sandbox\\Self\\AssertTest::repeatFail with data set #0")
        )
    }

    @Test
    fun standaloneFunctionIsExtracted() {
        assertEquals("someFunction", convert("php_qn://path/to/file.php::someFunction"))
    }

    @Test
    fun fileWithoutMethodSeparatorReturnsNull() {
        assertNull(convert("php_qn://path/to/file.php"))
    }

    @Test
    fun blankStringReturnsNull() {
        assertNull(convert(""))
    }

    @Test
    fun urlWithoutProtocolFollowsChainedSubstringLogic() {
        // No " with data set" and no "://", so substringAfter("://") keeps the whole string;
        // substringAfter("::", "") drops everything up to and including the FIRST "::".
        assertEquals("bar::baz", convert("foo::bar::baz"))
    }
}
