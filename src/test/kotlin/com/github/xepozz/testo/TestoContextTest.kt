package com.github.xepozz.testo

import com.intellij.codeInsight.template.impl.TemplateContextTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TestoContextTest : BasePlatformTestCase() {

    /** The live-template editor highlights a Testo template as PHP, the same way its base context does. */
    fun testHighlighterComesFromThePhpContext() {
        val context = TemplateContextTypes.getByClass(TestoContext::class.java)
        val base = checkNotNull(context.baseContextType) { "Testo context has no base context" }
        assertEquals("PHP", base.contextId)
        val highlighter = context.createHighlighter()
        assertNotNull("Testo templates are highlighted", highlighter)
        assertEquals(base.createHighlighter()?.javaClass, highlighter?.javaClass)
    }
}
