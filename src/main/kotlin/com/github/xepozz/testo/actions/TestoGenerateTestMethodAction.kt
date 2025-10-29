package com.github.xepozz.testo.actions

import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoFile
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.phpunit.PhpUnitTestDescriptor
import java.util.*
import kotlin.math.min

class TestoGenerateTestMethodAction : TestoGenerateMethodActionBase("Testo Test Method") {
    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile) = file.isTestoFile()

    override fun isValidForClass(testClass: PhpClass) = testClass.isTestoClass()

    override fun getProperties(testClass: PhpClass): Properties {
        val targetClassName = PhpUnitTestDescriptor.getTargetClassName(testClass)

        return Properties().apply {
            setProperty("TESTED_NAME", targetClassName ?: "")
            setProperty("NAME", "")
        }
    }

    override fun fillVariablesSegments(methodText: String, methodTemplate: Template) {
        var from = 0

        while (true) {
            val index = methodText.indexOf("\${CAPITALIZED_NAME}", from)
            if (index < 0) {
                methodTemplate.addTextSegment(methodText.substring(min(from, methodText.length - 1)))
                return
            }

            methodTemplate.addTextSegment(methodText.substring(from, index))
            if (from == 0) {
                methodTemplate.addVariable("name", ConstantNode("Name"), true)
            } else {
                methodTemplate.addVariableSegment("name")
            }

            from = index + "\${CAPITALIZED_NAME}".length
        }
    }
}
