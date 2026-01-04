package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpAttributesList
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import java.util.concurrent.ExecutionException
import javax.swing.Icon

class TestoTestRunLineMarkerProvider : RunLineMarkerContributor() {
    override fun producesAllPossibleConfigurations(file: PsiFile) = true

    override fun getInfo(leaf: PsiElement): Info? {
        val url = when (leaf.elementType) {
            PhpTokenTypes.kwYIELD -> getInfoKeyword(leaf)
            PhpTokenTypes.kwRETURN -> getInfoKeyword(leaf)
            PhpTokenTypes.IDENTIFIER -> getInfoIdentifier(leaf)
            else -> null
        } ?: return null

//        println("leaf: $leaf, url: $url")
        return withExecutorActions(getTestStateIcon(url, leaf.project, false))
    }

   private fun getInfoIdentifier(leaf: PsiElement): String? {
        val element = leaf.parent as? PhpPsiElement ?: return null

        return when {
            element is ClassReference && element.parent is PhpAttribute -> {
                val attribute = element.parent as PhpAttribute
                if (attribute.fqn !in runnableAttributes) return null

                val index = (attribute.parent as PhpAttributesList).attributes.indexOf(attribute)

                getInlineTestLocationHint(attribute.owner, index)
            }

            element is PhpNamedElement -> {
                if (element.nameIdentifier != leaf) return null

                getLocationInfo(element)
            }

            else -> null
        }
    }

    private fun getInfoKeyword(leaf: PsiElement): String? {
        val method = leaf.parentOfType<Method>()
        if (method?.isTestoDataProviderLike() != true) return null
        if (!TestoDataProviderUtils.isDataProvider(method)) return null

        var index = 0;
        var current: PsiElement? = leaf
        while (current != null) {
            current = PhpPsiUtil.findPrevSiblingOfAnyType(current, PhpTokenTypes.kwYIELD, PhpTokenTypes.kwRETURN)
            index++
        }


        return getDataProviderLocationHint(method) + "#" + index
    }

    companion object Companion {
        val runnableAttributes = arrayOf(TestoClasses.DATA_PROVIDER, TestoClasses.TEST_INLINE)
        fun getLocationHint(element: Function) = when (element) {
            is Method -> getLocationHint(element.containingClass!!) + "::" + element.name
            else -> getLocationHint(element.containingFile) + "::" + element.fqn
        }

        fun getLocationHint(element: PhpClass) = getLocationHint(element.containingFile) + "::" + element.fqn
        fun getLocationHint(file: PsiFile) = "${TestoFrameworkType.SCHEMA}://" + getFilePathDeploymentAware(file)
        fun getDataProviderLocationHint(function: Function) = getLocationHint(function) // + "::@" + function.name
        fun getInlineTestLocationHint(element: PsiElement, index: Int) = getLocationInfo(element) + "#" + index

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

        private fun getLocationInfo(element: PsiElement) = when {
            element is Function && element.isTestoExecutable() -> getLocationHint(element)
            element is PhpClass && element.isTestoClass() -> getLocationHint(element)
            element is Function && TestoDataProviderUtils.isDataProvider(element) -> getDataProviderLocationHint(element)
            else -> null
        }
    }
}