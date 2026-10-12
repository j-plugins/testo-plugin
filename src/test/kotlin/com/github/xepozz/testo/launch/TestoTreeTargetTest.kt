package com.github.xepozz.testo.launch

import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
        assertEquals(listOf("test"), selection.testoTypes)
    }

    @Test
    fun withoutAPathTheSelectorIsNotApplied() {
        val selection = TestoRunSelection(scope = TestoScope.CONFIGURATION_FILE)
        TestoRunContexts.applyTreeTarget(selection, TestoRunTarget("php_qn:///app/testo.php::\\App\\X", " ", null))

        assertEquals(TestoScope.CONFIGURATION_FILE, selection.scope)
        assertEquals(emptyList<String>(), selection.suites)
        assertEquals(emptyList<String>(), selection.testoTypes)
    }

    @Test
    fun aRunOfSeveralTypesIsNotTheRunOfANodeOfOneOfThem() {
        val contexts = TestoRunContexts(object : TestoRunContexts.Host {
            override fun baseSetup(selection: TestoRunSelection, element: PsiElement, file: VirtualFile): PsiElement? = null
            override fun baseMatches(selection: TestoRunSelection, element: PsiElement) = false
            override fun isForeignTestFile(file: PsiFile) = false
            override fun directoryHasPhpFiles(directory: PsiDirectory) = false
        })
        val selection = TestoRunSelection(
            scope = TestoScope.METHOD,
            methodName = "\\App\\CalcTest::med",
            testoTypes = listOf("test", "bench"),
        )
        val target = TestoRunTarget("php_qn:///app/tests/CalcTest.php::\\App\\CalcTest::med", null, "test")

        assertEquals(false, contexts.treeNodeMatches(selection, target, null))
        assertEquals(true, contexts.treeNodeMatches(selection.copy(testoTypes = listOf("test")), target, null))
    }
}
