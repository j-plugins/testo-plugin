package com.github.xepozz.testo.tests

import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoExecutable
import com.intellij.execution.lineMarker.ExecutorAction
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
import javax.swing.Icon

class TestoTestRunLineMarkerProvider : RunLineMarkerContributor() {
    override fun producesAllPossibleConfigurations(file: PsiFile) = true

    override fun getInfo(leaf: PsiElement): Info? {
        if (leaf.elementType != PhpTokenTypes.IDENTIFIER) return null
        val element = leaf.parent as? PhpNamedElement ?: return null
        if (element.nameIdentifier != leaf) return null

        return when {
            element is Function && element.isTestoExecutable() -> withExecutorActions(
                getTestStateIcon(getLocationHint(element), element.project, false),
            )

            element is PhpClass && element.isTestoClass() -> withExecutorActions(
                getTestStateIcon(getLocationHint(element), element.project, false),
            )

            element is Function && TestoDataProviderUtils.isDataProvider(element) -> withExecutorActions(
                getTestStateIcon(getDataProviderLocationHint(element), element.project, false),
            )

            else -> null
        }

    }

    companion object Companion {
        fun getLocationHint(element: Function) = when (element) {
            is Method -> getLocationHint(element.containingClass!!) + "::" + element.name
            else -> getLocationHint(element.containingFile) + "::" + element.fqn
        }

        fun getLocationHint(element: PhpClass) = getLocationHint(element.containingFile) + "::" + element.fqn
        fun getLocationHint(file: PsiFile) = "${TestoFrameworkType.SCHEMA}://" + getFilePathDeploymentAware(file)
        fun getDataProviderLocationHint(function: Function) = getLocationHint(function) + "::" + function.name

        fun getFilePathDeploymentAware(psiFile: PsiFile): String {
            val localPath = psiFile.virtualFile.path
            val remoteMapper = createPathMapper(psiFile.project)
            return when {
                remoteMapper.canProcess(localPath) -> remoteMapper.process(localPath)
                else -> localPath
            }
        }

        fun withExecutorActions(icon: Icon) = TestoTestRunLineMarkerProviderInfo(
            icon,
            ExecutorAction.getActions(),
            RUN_TEST_TOOLTIP_PROVIDER,
        )
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