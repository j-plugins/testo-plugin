package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.actions.TestoRunCommandAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import java.util.concurrent.ExecutionException

class TestoTestRunLineMarkerProvider : RunLineMarkerContributor() {
    override fun getInfo(leaf: PsiElement): Info? {
        if (leaf.elementType != PhpTokenTypes.IDENTIFIER) return null
        val element = leaf.parent as? PhpNamedElement ?: return null
        if (element.nameIdentifier != leaf) return null

        return when {
            element is Function && element.isTestoMethod() -> withExecutorActions(
                getTestStateIcon(
                    getLocationHint(element),
                    element.project,
                    false,
                ),
            )

            element is PhpClass && element.isTestoClass() -> withExecutorActions(
                getTestStateIcon(
                    getLocationHint(element),
                    element.project,
                    false,
                ),
            )

            else -> null
        }

    }

    companion object Companion {
        val RUN_TEST_TOOLTIP_PROVIDER = { it: PsiElement -> "Run Testo" }

        private fun getInfo(url: String, project: Project, isClass: Boolean) =
            Info(
                getTestStateIcon(url, project, isClass),
                arrayOf(TestoRunCommandAction("")),
                RUN_TEST_TOOLTIP_PROVIDER
            )

        fun getLocationHint(element: Function) = when (element) {
            is Method -> getLocationHint(element.containingClass!!) + "::" + element.name
            else -> getLocationHint(element.containingFile) + "::" + element.fqn
        }

        fun getLocationHint(element: PhpClass) = getLocationHint(element.containingFile) + "::" + element.fqn
        fun getLocationHint(file: PsiFile) = "${TestoFrameworkType.SCHEMA}://" + getFilePathDeploymentAware(file)

        fun getFilePathDeploymentAware(psiFile: PsiFile): String {
            val localPath = psiFile.virtualFile.path
            val remoteMapper = createPathMapper(psiFile.project)
            return when {
                remoteMapper.canProcess(localPath) -> remoteMapper.process(localPath)
                else -> localPath
            }
        }

//        fun getLocationHint(containingClass: PhpClass, method: Method, datasetName: String?) =
//            getLocationHint(containingClass) + "::" + method.name + " with data set " + datasetName

        fun createPathMapper(project: Project): PhpCommandLinePathProcessor {
            val interpreter = PhpProjectConfigurationFacade.getInstance(project).interpreter
            if (interpreter?.isRemote != true) return PhpCommandLinePathProcessor.LOCAL

            val data = interpreter.phpSdkAdditionalData
            val manager = PhpRemoteInterpreterManager.getInstance() ?: return PhpCommandLinePathProcessor.LOCAL

            return try {
                manager.createPathMapper(project, data)
            } catch (_: ExecutionException) {
                PhpCommandLinePathProcessor.LOCAL
            }
        }
    }
}
