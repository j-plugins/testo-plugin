package com.github.xepozz.testo.openide.actions

import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.actions.CodeInsightAction
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import ru.openide.openphp.lang.psi.symbols.PhpDeclarations
import ru.openide.openphp.lang.psi.symbols.valueOrNull
import java.util.Properties

/**
 * Generate → Test Method in a Testo class: the "Testo Test Method" code template, inserted after the member at the
 * caret — or at the top of the class body — as a live template whose name the user types, then formatted.
 */
class TestoGenerateTestMethodAction : CodeInsightAction(), CodeInsightActionHandler {
    private val php: TestoPhp get() = TestoPhp.getInstance()

    override fun getHandler(): CodeInsightActionHandler = this

    public override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean =
        file.isTestoFile() && classAtCaret(editor, file) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val testClass = classAtCaret(editor, file) ?: return
        val body = PhpDeclarations.classOf(testClass.psi).valueOrNull()?.body ?: return
        val offset = insertionOffset(body, file.findElementAt(editor.caretModel.offset))
        editor.caretModel.moveToOffset(offset)

        val properties = Properties().apply {
            setProperty("TESTED_NAME", "")
            setProperty("NAME", "")
        }
        val methodText = FileTemplateManager.getInstance(project).getCodeTemplate(TEMPLATE).getText(properties)
        val template = TemplateManager.getInstance(project).createTemplate("", "").apply {
            addTextSegment("\n")
            fillNameSegments(methodText, this)
            setToIndent(true)
            isToReformat = true
            isToShortenLongNames = true
        }
        TemplateManager.getInstance(project).startTemplate(editor, template)
    }

    override fun startInWriteAction(): Boolean = true

    private fun classAtCaret(editor: Editor, file: PsiFile): PhpClassView? =
        generateSequence(file.findElementAt(editor.caretModel.offset)) { it.parent }
            .takeWhile { it !is PsiFile }
            .firstNotNullOfOrNull { php.view(it) as? PhpClassView }
            ?.takeIf { it.psi.isTestoClass() }

    companion object {
        const val TEMPLATE = "Testo Test Method"

        private const val NAME_VARIABLE = "\${CAPITALIZED_NAME}"

        /** After the member of [body] the caret is in, or else after the last member before it, or else after `{`. */
        internal fun insertionOffset(body: PsiElement, atCaret: PsiElement?): Int {
            val member = atCaret?.let { leaf -> generateSequence(leaf) { it.parent }.firstOrNull { it.parent == body } }
            if (member != null && member.firstChild != null) return member.textRange.endOffset
            val before = member?.let { generateSequence(it.prevSibling) { s -> s.prevSibling }.firstOrNull { s -> s.firstChild != null } }
            return before?.textRange?.endOffset ?: (body.textOffset + 1)
        }

        /** Every `${CAPITALIZED_NAME}` of the template becomes one variable the user types once, starting as `Name`. */
        private fun fillNameSegments(text: String, template: Template) {
            var from = 0
            var first = true
            while (true) {
                val index = text.indexOf(NAME_VARIABLE, from)
                if (index < 0) {
                    if (from < text.length) template.addTextSegment(text.substring(from))
                    return
                }
                template.addTextSegment(text.substring(from, index))
                if (first) template.addVariable("name", ConstantNode("Name"), true) else template.addVariableSegment("name")
                first = false
                from = index + NAME_VARIABLE.length
            }
        }
    }
}
