package com.github.xepozz.testo.actions

import com.github.xepozz.testo.isTestoClass
import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.actions.CodeInsightAction
import com.intellij.codeInsight.generation.actions.GenerateActionPopupTemplateInjector
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.ide.fileTemplates.impl.AllFileTemplatesConfigurable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.lang.PhpCodeUtil
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocComment
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.parser.PhpStubElementTypes
import com.jetbrains.php.lang.psi.PhpCodeEditUtil
import com.jetbrains.php.lang.psi.PhpPsiElementFactory
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import java.util.*

abstract class TestoGenerateMethodActionBase(
    val templateName: String,
) : CodeInsightAction(),
    CodeInsightActionHandler,
    GenerateActionPopupTemplateInjector {
    override fun getHandler() = this

    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile) = findTestClass(editor, file)
        ?.let { isValidForClass(it) }
        ?: false

    protected open fun isValidForClass(testClass: PhpClass) = true

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val testClass: PhpClass = checkNotNull(findTestClass(editor, file))

        val methodTemplate = PhpPsiElementFactory.createMethod(project, "public function testFoo(){}")
        val anchor = findAnchor(testClass, file.findElementAt(editor.caretModel.offset))

        val psiElement = when {
            anchor != null -> testClass.addAfter(methodTemplate, anchor)
            else -> PhpCodeEditUtil.insertClassMember(testClass, methodTemplate)
        }
        val dummyMethodPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(
            psiElement, file
        )
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
        val dummyMethod = dummyMethodPointer.getElement()
        if (dummyMethod != null) {
            val range = dummyMethod.textRange
            editor.document.replaceString(range.startOffset, range.endOffset, "")
            editor.caretModel.moveToOffset(range.startOffset)
            this.insertTestMethod(project, editor, this.getProperties(testClass))
        }
    }

    private fun insertTestMethod(project: Project, editor: Editor, properties: Properties?) {
        val methodText = PhpCodeUtil.getCodeTemplate(this.templateName, properties, project)
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        this.fillVariablesSegments(methodText, template)
        template.setToIndent(true)
        template.isToReformat = true
        template.isToShortenLongNames = true
        TemplateManager.getInstance(project).startTemplate(editor, template, object : TemplateEditingAdapter() {
            override fun templateFinished(template1: Template, brokenOff: Boolean) {
                PhpCodeEditUtil.setupMethodBody(project)
            }
        })
    }

    protected open fun getProperties(aClass: PhpClass): Properties? {
        return null
    }

    protected open fun fillVariablesSegments(methodText: String, methodTemplate: Template) {
        methodTemplate.addTextSegment(methodText)
    }

    override fun createEditTemplateAction(dataContext: DataContext): AnAction? {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return null
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return null
        if (!this.isValidForFile(project, editor, file)) return null

        return object : AnAction(PhpBundle.message("edit.template")) {
            override fun actionPerformed(e: AnActionEvent) {
                AllFileTemplatesConfigurable.editCodeTemplate(templateName, e.project)
            }
        }
    }

    private fun findTestClass(editor: Editor, file: PsiFile): PhpClass? {
        val aClass = PhpCodeEditUtil.findClassAtCaret(editor, file)
        return aClass?.takeIf { it.isTestoClass() }
    }

    companion object Companion {
        private fun findAnchor(testClass: PhpClass, elementAt: PsiElement?): PsiElement? {
            if (elementAt != null) {
                val parentAnchor = PhpPsiUtil.getParentByCondition<PsiElement?>(
                    elementAt,
                    false,
                    { canBeAnchor(it, testClass) },
                    null,
                )

                val result = parentAnchor
                    ?: PhpPsiUtil.getPrevSiblingByCondition(elementAt) { canBeAnchor(it, testClass) }

                if (result != null) {
                    return when (result) {
                        is PhpDocComment -> PhpPsiUtil.getNextSiblingIgnoreWhitespace(
                            result.nextSibling,
                            true
                        )

                        else -> result
                    }
                }
            }

            return PhpPsiUtil.getPrevSiblingIgnoreWhitespace(testClass.lastChild, true)
        }

        private fun canBeAnchor(element: PsiElement, testClass: PhpClass?): Boolean {
            return (element is PsiComment || PhpPsiUtil.isOfType(
                element,
                *arrayOf(
                    PhpElementTypes.CLASS_FIELDS,
                    PhpElementTypes.CLASS_CONSTANTS,
                    PhpStubElementTypes.CLASS_METHOD,
                    PhpTokenTypes.chLBRACE
                )
            )) && element.parent === testClass
        }
    }
}
