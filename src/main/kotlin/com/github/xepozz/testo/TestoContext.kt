package com.github.xepozz.testo

import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.PsiElement

/** Inside the body of a class in a Testo file. The base context (`baseContextId="PHP"`) is set in plugin.xml. */
class TestoContext : TemplateContextType("Testo") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val element = templateActionContext.file.findElementAt(templateActionContext.startOffset) ?: return false
        return isInContext(element)
    }

    // The template editor highlights a template the way its context does; PHP's context knows how.
    override fun createHighlighter(): SyntaxHighlighter? = baseContextType?.createHighlighter()

    fun isInContext(element: PsiElement): Boolean {
        val parent = TestoPhp.getInstance().view(element.parent) as? PhpClassView ?: return false
        if (!element.containingFile.isTestoFile()) return false
        val bodyStart = parent.bodyStartOffset ?: return false
        return bodyStart <= element.textOffset
    }
}
