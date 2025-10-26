package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoClasses
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.asSafely
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.parser.PhpElementTypes
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.phpunit.PhpUnitTestDescriptor
import com.jetbrains.php.testFramework.run.PhpTestConfigurationProducer

class TestoRunConfigurationProducer : PhpTestConfigurationProducer<TestoRunConfiguration>(
    TestoTestRunnerSettingsValidator,
    FILE_TO_SCOPE,
    METHOD_NAMER,
    METHOD,
) {
    override fun isEnabled(project: Project) = true

    override fun getWorkingDirectory(element: PsiElement): VirtualFile? {
        if (element is PsiDirectory) {
            return element.parentDirectory?.virtualFile
        }

        return element.containingFile?.containingDirectory?.virtualFile
    }

    override fun getConfigurationFactory() = TestoRunConfigurationFactory(TestoRunConfigurationType.INSTANCE)

    companion object Companion {
        val METHOD = Condition<PsiElement> {
            (it.elementType == PhpTokenTypes.IDENTIFIER)
                    && (it.parent is Function && (it.parent as Function).getAttributes(TestoClasses.TEST).isNotEmpty())
        }
        private val METHOD_NAMER = { element: PsiElement? -> (element as? Function)?.name }
        private val FILE_TO_SCOPE = { file: PsiFile? ->
            println("file to scope: ${file?.virtualFile?.name}")
            file
                .asSafely<PhpFile>()
                ?.let { phpFile ->
                    PsiTreeUtil.findChildrenOfType(phpFile, PhpClass::class.java)
                        .firstOrNull { PhpUnitTestDescriptor.INSTANCE.isTestClassName(it.name) }
                }
        }
    }
}
