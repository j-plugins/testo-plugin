package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.groupNamesOf
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.php.PhpClassReferenceView
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpDeclarationView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.PhpLeafKind
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

class TestoTestRunLineMarkerProvider : RunLineMarkerContributor() {
    override fun producesAllPossibleConfigurations(file: PsiFile) = true

    override fun getInfo(leaf: PsiElement): Info? {
        val url = when (php.leafKind(leaf)) {
            PhpLeafKind.YIELD, PhpLeafKind.RETURN -> getInfoKeyword(leaf)
            PhpLeafKind.IDENTIFIER -> getInfoIdentifier(leaf)
            null -> null
        } ?: return null

        return withExecutorActions(getTestStateIcon(url, leaf.project, false))
    }

    private fun getInfoIdentifier(leaf: PsiElement): String? {
        val element = leaf.parent ?: return null

        return when (val view = php.view(element)) {
            is PhpClassReferenceView -> {
                val attribute = view.attribute
                when {
                    view.newExpression != null && view.fqn == TestoClasses.APPLICATION_CONFIG -> {
                        getLocationHint(element.containingFile)
                    }

                    view.newExpression != null && view.fqn == TestoClasses.SUITE_CONFIG -> {
                        getLocationHint(element.containingFile)
                    }

                    attribute != null -> {
                        // `#[Group]` marks membership, it is not a test on its own: running it means running every
                        // test of that group (`--group=<name>`). The hint points at the annotated element only so the
                        // gutter icon can show its last state; a group on something unrecognized falls back to the
                        // file. No resolvable names — no icon: the producer would refuse such a context and the icon
                        // would offer a run that does nothing.
                        if (attribute.fqn == TestoClasses.FILTER_GROUP) {
                            if (groupNamesOf(attribute).isEmpty()) return null
                            return getLocationInfo(attribute.owner) ?: getLocationHint(attribute.psi.containingFile)
                        }
                        if (attribute.fqn !in RUNNABLE_ATTRIBUTES) return null

                        val attributesOwner = attribute.owner ?: return null
                        val index = PsiUtil.getAttributeOrder(attribute.psi, attributesOwner)
                        if (index < 0) getLocationInfo(attributesOwner)
                        else getInlineTestLocationHint(attributesOwner, index)
                    }

                    else -> null
                }
            }

            is PhpDeclarationView -> {
                if (view.nameIdentifier != leaf) return null

                getLocationInfo(element)
            }

            else -> null
        }
    }

    private fun getInfoKeyword(leaf: PsiElement): String? {
        val method = generateSequence(leaf.parent) { it.parent }
            .firstOrNull { (php.view(it) as? PhpFunctionView)?.isMethod == true }
        if (method?.isTestoDataProviderLike() != true) return null
        if (!TestoDataProviderUtils.isDataProvider(method)) return null

        val index = php.precedingExitKeywords(leaf) + 1

        return getDataProviderLocationHint(method) + "#" + index
    }

    companion object Companion {
        private val php: TestoPhp get() = TestoPhp.getInstance()

        val RUNNABLE_ATTRIBUTES = arrayOf(
            *TestoClasses.TEST_ATTRIBUTES,
            *TestoClasses.BENCH_ATTRIBUTES,
            *TestoClasses.DATA_ATTRIBUTES,
            *TestoClasses.TEST_CASE_ATTRIBUTES,
        )

        /** The address of a class, a method or a function. */
        fun getLocationHint(element: PsiElement): String = when (val view = php.view(element)) {
            is PhpClassView -> getLocationHint(view)
            is PhpFunctionView -> getLocationHint(view)
            else -> error("No test address for ${element.javaClass.simpleName}")
        }

        fun getLocationHint(function: PhpFunctionView): String {
            val cls = function.containingClass
            return when {
                function.isMethod -> getLocationHint(checkNotNull(cls) { "Method ${function.name} has no class" }) + "::" + function.name
                else -> getLocationHint(function.psi.containingFile) + "::" + function.fqn
            }
        }

        fun getLocationHint(cls: PhpClassView) = getLocationHint(cls.psi.containingFile) + "::" + cls.fqn
        fun getLocationHint(file: PsiFile) = "${TestoLocationHints.SCHEMA}://" + getFilePathDeploymentAware(file)
        fun getDataProviderLocationHint(function: PsiElement) = getLocationHint(function) // + "::@" + function.name
        fun getInlineTestLocationHint(element: PsiElement, index: Int) = getLocationInfo(element) + "#" + index

        fun getFilePathDeploymentAware(psiFile: PsiFile): String =
            php.projectPaths(psiFile.project).toEnvironment(psiFile.virtualFile.path)

        fun withExecutorActions(icon: Icon) = TestoTestRunLineMarkerProviderInfo(
            icon,
            ExecutorAction.getActions(),
            RUN_TEST_TOOLTIP_PROVIDER,
        )
//        fun getLocationHint(containingClass: PhpClass, method: Method, datasetName: String?) =
//            getLocationHint(containingClass) + "::" + method.name + " with data set " + datasetName

        private fun getLocationInfo(element: PsiElement?): String? = when (val view = php.view(element)) {
            is PhpFunctionView if element!!.isTestoExecutable() -> getLocationHint(view)
            is PhpClassView if element!!.isTestoClass() -> getLocationHint(view)
            is PhpFunctionView if TestoDataProviderUtils.isDataProvider(element!!) -> getLocationHint(view)
            else -> null
        }
    }
}
