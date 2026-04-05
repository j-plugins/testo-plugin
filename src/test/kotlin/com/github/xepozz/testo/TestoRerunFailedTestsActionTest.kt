package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.actions.TestoRerunFailedTestsAction
import junit.framework.TestCase

class TestoRerunFailedTestsActionTest : TestCase() {

    fun testExtractFilter_classAndMethod() {
        val url = "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\App\\Tests\\FooTest::testBar"
        val result = TestoRerunFailedTestsAction.extractFilter(url)
        assertEquals("\\App\\Tests\\FooTest::testBar", result)
    }

    fun testExtractFilter_classOnly_returnsNull() {
        val url = "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\App\\Tests\\FooTest"
        val result = TestoRerunFailedTestsAction.extractFilter(url)
        assertNull(result)
    }

    fun testExtractFilter_fileOnly_returnsNull() {
        val url = "${TestoFrameworkType.SCHEMA}:///path/to/file.php"
        val result = TestoRerunFailedTestsAction.extractFilter(url)
        assertNull(result)
    }

    fun testExtractFilter_emptyUrl_returnsNull() {
        val result = TestoRerunFailedTestsAction.extractFilter("")
        assertNull(result)
    }

    fun testExtractFilter_withoutSchemaPrefix() {
        val url = "/path/to/file.php::\\App\\Tests\\FooTest::testBar"
        val result = TestoRerunFailedTestsAction.extractFilter(url)
        assertEquals("\\App\\Tests\\FooTest::testBar", result)
    }

    fun testBuildFilterArguments_skipsClassLevelUrls() {
        val urls = listOf(
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\Lifecycle\\Self\\BeforeAfterClass",
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\Lifecycle\\Self\\BeforeAfterClass::firstTest",
        )
        val arguments = TestoRerunFailedTestsAction.buildFilterArguments(urls)
        assertEquals(listOf("--filter", "\\Tests\\Lifecycle\\Self\\BeforeAfterClass::firstTest"), arguments)
    }

    fun testBuildFilterArguments_multipleFailedMethods() {
        val urls = listOf(
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\FooTest::testA",
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\FooTest::testB",
            "${TestoFrameworkType.SCHEMA}:///path/to/other.php::\\Tests\\BarTest::testC",
        )
        val arguments = TestoRerunFailedTestsAction.buildFilterArguments(urls)
        assertEquals(
            listOf(
                "--filter", "\\Tests\\FooTest::testA",
                "--filter", "\\Tests\\FooTest::testB",
                "--filter", "\\Tests\\BarTest::testC",
            ),
            arguments,
        )
    }

    fun testBuildFilterArguments_emptyList() {
        val arguments = TestoRerunFailedTestsAction.buildFilterArguments(emptyList())
        assertTrue(arguments.isEmpty())
    }

    fun testBuildFilterArguments_allClassLevel_returnsEmpty() {
        val urls = listOf(
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\FooTest",
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\BarTest",
        )
        val arguments = TestoRerunFailedTestsAction.buildFilterArguments(urls)
        assertTrue(arguments.isEmpty())
    }

    fun testBuildFilterArguments_mixedClassAndMethodLevel() {
        val urls = listOf(
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\FooTest",
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\FooTest::testA",
            "${TestoFrameworkType.SCHEMA}:///path/to/file.php::\\Tests\\BarTest",
            "${TestoFrameworkType.SCHEMA}:///path/to/other.php::\\Tests\\BarTest::testB",
        )
        val arguments = TestoRerunFailedTestsAction.buildFilterArguments(urls)
        assertEquals(
            listOf(
                "--filter", "\\Tests\\FooTest::testA",
                "--filter", "\\Tests\\BarTest::testB",
            ),
            arguments,
        )
    }
}
