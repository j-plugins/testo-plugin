package com.github.xepozz.testo.infection

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TestoMutationBoundariesPsiTest : BasePlatformTestCase() {
    private fun assertTarget(marked: String, needle: String) {
        val start = marked.indexOf("<target>")
        val end = marked.indexOf("</target>") - "<target>".length
        val source = marked.replace("<target>", "").replace("</target>", "")
        val file = myFixture.configureByText("Mutation.php", source)
        val document = myFixture.editor.document
        val offset = source.indexOf(needle)
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val first = (lineStart..offset).first { !source[it].isWhitespace() }
        val selected = statementAt(file.findElementAt(first), document)
        assertEquals(source, TextRange(start, end), selected?.range)
        assertEquals(source.substring(start, end), selected?.range?.substring(source))
    }

    fun testSingleBodiesAndAlternativeLists() {
        for (header in listOf("if (true)", "while (true)", "for (;;)", "foreach ([] as ${'$'}x)", "if (true) {} else")) {
            assertTarget("<?php\n<target>$header\n return 1;</target>", "return")
        }
        assertTarget("<?php\nif (true):\n <target>return 1;</target>\nendif;", "return")
        assertTarget("<?php\nswitch (1) { case 1:\n <target>return 1;</target>\n}", "return")
    }

    fun testMultipleDeclaratorsAndLeadingSeparators() {
        for (separator in listOf(", ", ",\n ", "\n , /* between */ ")) {
            assertTarget("<?php\n<target>const A = 1${separator}B = 2;</target>", "B =")
            assertTarget("<?php\nclass C {\n<target>public const A = 1${separator}B = 2;</target>\n}", "B =")
            assertTarget("<?php\nclass C {\n<target>public ${'$'}a = 1${separator}${'$'}b = 2;</target>\n}", "${'$'}b")
        }
    }

    fun testAttributedInlineMethodAndNamespacedStatement() {
        assertTarget("<?php\nclass C {\n <target>#[Attr(1)]\n public function f() { return 2; }</target>\n}", "return")
        assertTarget("<?php\nnamespace N {\n<target>const A = 1,\n B = 2;</target>\n}", "B =")
    }

    fun testAnonymousClassSemicolonNamespaceAndBrokenExpression() {
        assertTarget("<?php\n${'$'}object = new class {\n public function f() {\n  <target>return 1;</target>\n }\n};", "return")
        assertTarget("<?php\nnamespace N;\nfunction f() {\n <target>return 1;</target>\n}", "return")
        assertTarget("<?php\nfunction f() {\n <target>return 1 + ;</target>\n}", "return")
    }
}
