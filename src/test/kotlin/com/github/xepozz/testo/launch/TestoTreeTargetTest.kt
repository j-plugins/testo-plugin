package com.github.xepozz.testo.launch

import com.github.xepozz.testo.tests.console.TestoRunTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class TestoTreeTargetTest {

    @Test
    fun aNodeLaysItsSuiteTypeAndSelectorOverTheRun() {
        val selection = TestoRunSelection(scope = TestoScope.FILE, filePath = "/app/tests/CalcTest.php")
        TestoRunContexts.applyTreeTarget(selection, TestoRunTarget("php_qn:///app/tests/CalcTest.php::\\App\\CalcTest::med:0:1", "Unit", "test"))

        assertEquals(TestoScope.METHOD, selection.scope)
        assertEquals("\\App\\CalcTest::med:0:1", selection.methodName)
        assertEquals(listOf("Unit"), selection.suites)
        assertEquals("test", selection.testoType)
    }

    @Test
    fun withoutAPathTheSelectorIsNotApplied() {
        val selection = TestoRunSelection(scope = TestoScope.CONFIGURATION_FILE)
        TestoRunContexts.applyTreeTarget(selection, TestoRunTarget("php_qn:///app/testo.php::\\App\\X", " ", null))

        assertEquals(TestoScope.CONFIGURATION_FILE, selection.scope)
        assertEquals(emptyList<String>(), selection.suites)
        assertEquals("", selection.testoType)
    }
}
