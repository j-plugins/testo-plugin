package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.TestoUtil
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.launch.TestoRunContexts
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.testframework.sm.runner.SMRunnerConsolePropertiesProvider
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.ui.SimpleListCellRenderer
import ru.openide.openphp.lang.psi.elements.PhpClassDeclaration
import ru.openide.openphp.lang.psi.symbols.PhpDeclarations
import ru.openide.openphp.run.testing.PhpTestFrameworks

/**
 * Runs from a source context. The decisions are Testo's own ([TestoRunContexts]); where Testo has no rule — a plain
 * test method, a file, a directory — the [Host] here answers the way PhpStorm's test producer does, so both
 * implementations produce the same runs.
 */
class TestoRunConfigurationProducer : LazyRunConfigurationProducer<TestoRunConfiguration>() {
    private val php: TestoPhp get() = TestoPhp.getInstance()

    private val contexts = TestoRunContexts(OpenIdeHost())

    override fun getConfigurationFactory(): ConfigurationFactory = TestoRunConfigurationType.instance()

    override fun setupConfigurationFromContext(
        configuration: TestoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        if (!TestoUtil.isEnabled(context.project)) return false
        val element = contexts.findTestElement(context.psiLocation) ?: return false
        val file = fileOf(element) ?: return false

        val selection = configuration.selection
        val result = contexts.setup(selection, element, file) ?: return false
        // A results-tree node carries what its source element cannot: the suite, the type, the exact selector.
        TestoRunContexts.treeTarget(context)?.let { TestoRunContexts.applyTreeTarget(selection, it) }
        configuration.selection = selection
        // A class is reached through the node of its body; the run stands for the declaration, as on PhpStorm.
        sourceElement.set((php.view(result) as? PhpClassView)?.psi ?: result)
        configuration.setGeneratedName()
        return true
    }

    override fun isConfigurationFromContext(configuration: TestoRunConfiguration, context: ConfigurationContext): Boolean {
        if (!TestoUtil.isEnabled(context.project)) return false
        val location = context.psiLocation ?: return false
        val selection = configuration.selection
        TestoRunContexts.treeTarget(context)?.let { target ->
            contexts.treeNodeMatches(selection, target, location)?.let { return it }
        }
        val element = contexts.findTestElement(location) ?: return false
        return contexts.matches(selection, element)
    }

    // A Testo run from a Testo context wins over any run that is no test run — running the file as a PHP script
    // above all. Another test run is left to the platform's order.
    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext): Boolean =
        other.configuration !is SMRunnerConsolePropertiesProvider

    /**
     * What a source element leaves open is asked before the first run: which subclass of an abstract case runs, which
     * test a shared data provider feeds. A results-tree node leaves nothing open.
     */
    override fun onFirstRun(configuration: ConfigurationFromContext, context: ConfigurationContext, startRunnable: Runnable) {
        if (TestoRunContexts.treeTarget(context) != null) return startRunnable.run()

        val testo = configuration.configuration as TestoRunConfiguration
        val element = context.psiLocation?.let(contexts::findTestElement)
            ?: return super.onFirstRun(configuration, context, startRunnable)

        val testTarget = (php.view(element) as? PhpAttributeView)?.takeIf { it.fqn != TestoClasses.FILTER_GROUP }?.owner ?: element
        abstractClassOf(testTarget)?.let { abstractClass ->
            val inheritors = php.allSubclasses(abstractClass.psi.project, abstractClass.fqn).filter { it.psi.isTestoClass() }
            val name = (php.view(testTarget) as? PhpClassView)?.name ?: (php.view(testTarget) as? PhpFunctionView)?.name
            return choose(context, "Choose a class to run $name", inheritors.map { it.psi }) { inheritor ->
                val selection = testo.selection
                TestoRunContexts.applyInheritorChoice(selection, testTarget, inheritor)
                testo.selection = selection
                testo.setGeneratedName()
                startRunnable.run()
            }
        }

        dataProviderOf(element)?.let { (provider, dataSetIndex) ->
            val tests = TestoDataProviderUtils.findDataProviderUsages(provider)
                .filter { (php.view(it) as? PhpFunctionView)?.isMethod == true }
            if (tests.size > 1) {
                return choose(context, "Choose a test to run with ${(php.view(provider) as PhpFunctionView).name}", tests) { test ->
                    val selection = testo.selection
                    val index = TestoDataProviderUtils.findDataProviderUsagesIndex(test, provider)
                    TestoRunContexts.applyDataSetUsage(selection, test, index, dataSetIndex)
                    testo.selection = selection
                    testo.setGeneratedName()
                    startRunnable.run()
                }
            }
        }

        super.onFirstRun(configuration, context, startRunnable)
    }

    /** The abstract class a run from [element] — a class or its method — stands for. */
    private fun abstractClassOf(element: PsiElement): PhpClassView? {
        val cls = when (val view = php.view(element)) {
            is PhpClassView -> view
            is PhpFunctionView -> view.containingClass
            else -> null
        }
        return cls?.takeIf { it.isAbstract }
    }

    /** The data provider a run from [element] starts at, with the data set a `yield` names (-1 for none). */
    private fun dataProviderOf(element: PsiElement): Pair<PsiElement, Int>? {
        if (php.isYield(element)) {
            val function = generateSequence(element.parent) { it.parent }.firstOrNull { php.view(it) is PhpFunctionView }
                ?: return null
            return function.takeIf { it.isTestoDataProviderLike() }?.let { it to PsiUtil.getExitStatementOrder(element, it) }
        }
        return element.takeIf { php.view(it) is PhpFunctionView && it.isTestoDataProviderLike() }?.let { it to -1 }
    }

    private fun choose(context: ConfigurationContext, title: String, elements: List<PsiElement>, chosen: (PsiElement) -> Unit) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(elements)
            .setTitle(title)
            .setRenderer(SimpleListCellRenderer.create("") { element ->
                val cls = php.view(element) as? PhpClassView
                val function = php.view(element) as? PhpFunctionView
                cls?.fqn?.removePrefix("\\") ?: function?.let { "${it.containingClass?.name.orEmpty()}::${it.name}" }.orEmpty()
            })
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .setItemChosenCallback(chosen)
            .createPopup()
            .showInBestPositionFor(context.dataContext)
    }

    private companion object {
        /** The file type PHP files have on either implementation's PHP plugin. */
        const val PHP_FILE_TYPE = "PHP"
    }

    private fun fileOf(element: PsiElement): VirtualFile? =
        (element as? PsiFileSystemItem)?.virtualFile ?: element.containingFile?.virtualFile

    /** What PhpStorm's test producer does where Testo has no rule of its own, and the checks only the IDE makes. */
    private inner class OpenIdeHost : TestoRunContexts.Host {
        override fun baseSetup(selection: TestoRunSelection, element: PsiElement, file: VirtualFile): PsiElement? = when {
            element is PsiDirectory -> {
                selection.scope = TestoScope.DIRECTORY
                selection.directoryPath = element.virtualFile.presentableUrl
                element
            }
            element is PsiFile -> element.takeIf { it.isTestoFile() }?.also {
                selection.scope = TestoScope.FILE
                selection.filePath = it.virtualFile.presentableUrl
            }
            isRunnableFunction(element) -> {
                selection.scope = TestoScope.METHOD
                selection.filePath = element.containingFile.virtualFile.presentableUrl
                selection.methodName = (php.view(element) as PhpFunctionView).name
                element
            }
            else -> null
        }

        override fun baseMatches(selection: TestoRunSelection, element: PsiElement): Boolean = when {
            element is PsiDirectory ->
                selection.scope == TestoScope.DIRECTORY && selection.directoryPath == element.virtualFile.presentableUrl
            element is PsiFile ->
                selection.scope == TestoScope.FILE && selection.filePath == element.virtualFile.presentableUrl
            isRunnableFunction(element) ->
                selection.scope == TestoScope.METHOD
                    && selection.filePath == element.containingFile.virtualFile.presentableUrl
                    && selection.methodName == (php.view(element) as PhpFunctionView).name
            else -> false
        }

        /** A file with a test class of another framework the test framework SPI knows — PHPUnit, say. */
        override fun isForeignTestFile(file: PsiFile): Boolean {
            val others = PhpTestFrameworks.all().filter { it.id != TestoFrameworkType.ID }
            if (others.isEmpty()) return false
            return PhpDeclarations.inFile(file, topLevelOnly = false)
                .filterIsInstance<PhpClassDeclaration>()
                .any { cls -> others.any { it.descriptor.isTestClass(cls) } }
        }

        override fun directoryHasPhpFiles(directory: PsiDirectory): Boolean {
            var found = false
            VfsUtilCore.iterateChildrenRecursively(directory.virtualFile, null) { file ->
                found = !file.isDirectory && file.fileType.name == PHP_FILE_TYPE
                !found
            }
            return found
        }

        /** A test, a benchmark, or a method feeding tests as a data provider — what PhpStorm's producer runs by name. */
        private fun isRunnableFunction(element: PsiElement): Boolean {
            val function = php.view(element) as? PhpFunctionView ?: return false
            return element.isTestoExecutable() || (function.isMethod && TestoDataProviderUtils.isDataProvider(element))
        }
    }
}
