package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.phpunit.PhpMethodLocation
import com.jetbrains.php.testFramework.run.PhpTestConfigurationProducer
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings

class TestoRunConfigurationProducer : PhpTestConfigurationProducer<TestoRunConfiguration>(
    TestoTestRunnerSettingsValidator,
    FILE_TO_SCOPE,
    METHOD_NAMER,
    METHOD,
) {
    override fun isEnabled(project: Project) = true


    override fun setupConfiguration(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement,
        virtualFile: VirtualFile
    ): PsiElement? {
        if (element is Method) {
            val element = findTestElement(element, getWorkingDirectory(element))
            if (element is Method) {
                val usages = TestoDataProviderUtils.findDataProviderUsages(element)

                if (usages.isNotEmpty()) {
                    val target = usages.first()

                    return super.setupConfiguration(testRunnerSettings, target, target.containingFile.virtualFile)
                }
            }
        }
        return super.setupConfiguration(testRunnerSettings, element, virtualFile)
    }

    override fun isConfigurationFromContext(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement
    ): Boolean {
        if (element is Method) {
            val usages = TestoDataProviderUtils.findDataProviderUsages(element)

            if (usages.isNotEmpty()) {
                val target = usages.first()

                return when {
                    testRunnerSettings.scope != PhpTestRunnerSettings.Scope.Method -> false
                    testRunnerSettings.methodName != this.myMethodNameProvider.`fun`(target) -> false
                    testRunnerSettings.filePath != target.containingFile.virtualFile.path -> false
                    else -> true
                }
            }
        }
        return super.isConfigurationFromContext(testRunnerSettings, element)
    }

    override fun getWorkingDirectory(element: PsiElement): VirtualFile? {
        if (element is PsiDirectory) {
            return element.parentDirectory?.virtualFile
        }

        return element.containingFile?.containingDirectory?.virtualFile
    }

    override fun getConfigurationFactory() = TestoRunConfigurationFactory(TestoRunConfigurationType.INSTANCE)

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext) = true

    companion object Companion {
        val METHOD = Condition<PsiElement> {
            it.isTestoExecutable() || (it is Method && TestoDataProviderUtils.isDataProvider(it))
        }
        private val METHOD_NAMER = { element: PsiElement? -> (element as? Function)?.name }
        private val FILE_TO_SCOPE = { file: PsiFile? ->
            file
                ?.takeIf { it.isTestoFile() }
                .apply { println("file to scope: $file -> $this") }
        }
    }
}
