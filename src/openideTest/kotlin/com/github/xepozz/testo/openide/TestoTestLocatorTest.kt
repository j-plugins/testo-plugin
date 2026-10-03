package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.TestoTestLocator
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.run.testing.PhpTestPathTranslator

/** Where a results-tree node leads, from the addresses Testo reports: the declarations, with its coordinates cut off. */
class TestoTestLocatorTest : BasePlatformTestCase() {
    private val locator = TestoTestLocator(PhpTestPathTranslator.IDENTITY)

    private fun where(path: String): String {
        val locations = locator.getLocation("php_qn", path, project, GlobalSearchScope.allScope(project))
        val element = locations.singleOrNull()?.psiElement ?: return locations.size.toString() + " locations"
        return when (val view = TestoPhp.getInstance().view(element)) {
            is PhpFunctionView -> if (view.isMethod) "method ${view.name}" else "function ${view.name}"
            is PhpClassView -> "class ${view.name}"
            else -> if (element is PsiFile) "file ${element.name}" else element.text
        }
    }

    fun testNodesLeadToTheirDeclarations() {
        val calc = myFixture.addFileToProject(
            "tests/CalcTest.php",
            "<?php\nnamespace App;\n\nfinal class CalcTest\n{\n    public function median(): void {}\n}\n",
        ).virtualFile.path
        val functions = myFixture.addFileToProject(
            "tests/functions.php",
            "<?php\nnamespace App;\n\nfunction medianOf(): void {}\n",
        ).virtualFile.path

        assertEquals("method median", where("$calc::\\App\\CalcTest::median"))
        assertEquals("method median", where("$calc::\\App\\CalcTest::median with data set #1"))
        assertEquals("method median", where("$calc::\\App\\CalcTest::median#2"))
        assertEquals("A data set coordinate names no member: the class", "class CalcTest", where("$calc::\\App\\CalcTest::med:3:0"))
        assertEquals("class CalcTest", where("$calc::\\App\\CalcTest"))
        assertEquals("function medianOf", where("$functions::\\App\\medianOf"))
        assertEquals("function medianOf", where("$functions::\\App\\medianOf:0:1"))
    }
}
