package com.github.xepozz.testo.launch

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.groupNamesOf
import com.github.xepozz.testo.hasAttribute
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoBench
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoConfigFile
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.isTestoFunction
import com.github.xepozz.testo.isTestoMethod
import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.PhpClassReferenceView
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement

/**
 * The run a source element produces, and whether a saved run is the one an element produces: the decisions of Testo's
 * run producer, over a [TestoRunSelection]. What Testo has no rule of its own for — a plain method, a file, a
 * directory — is answered by the IDE's own test producer, reached through [Host].
 */
class TestoRunContexts(private val host: Host) {

    /** What the IDE's test producer does where Testo has no rule, and the two checks only the IDE can make. */
    interface Host {
        /** The IDE producer's run for [element], written into [selection]; the element the run stands for, or null. */
        fun baseSetup(selection: TestoRunSelection, element: PsiElement, file: VirtualFile): PsiElement?

        /** Whether the IDE producer takes [selection] for the run [element] produces. */
        fun baseMatches(selection: TestoRunSelection, element: PsiElement): Boolean

        /** Whether [file] holds another framework's tests, where Testo offers no run. */
        fun isForeignTestFile(file: PsiFile): Boolean

        /** Whether [directory] holds PHP files a run could find. */
        fun directoryHasPhpFiles(directory: PsiDirectory): Boolean
    }

    private val php: TestoPhp get() = TestoPhp.getInstance()

    fun setup(selection: TestoRunSelection, element: PsiElement, file: VirtualFile): PsiElement? {
        val view = php.view(element)

        if (view is PhpClassReferenceView && view.newExpression != null && view.fqn == TestoClasses.APPLICATION_CONFIG) {
            selection.scope = TestoScope.CONFIGURATION_FILE
            selection.useAlternativeConfigurationFile = true
            selection.configurationFilePath = file.path
            return element
        }
        if (view is PhpClassReferenceView && view.newExpression != null && view.fqn == TestoClasses.SUITE_CONFIG) {
            val suiteName = view.newExpression?.firstStringArgument ?: return null
            selection.scope = TestoScope.CONFIGURATION_FILE
            selection.useAlternativeConfigurationFile = true
            selection.configurationFilePath = file.path
            selection.suites = listOf(suiteName)
            return element
        }
        if (view is PhpAttributeView && view.fqn == TestoClasses.FILTER_GROUP) {
            val groups = groupNamesOf(view)
            if (groups.isEmpty()) return null

            // A group run is deliberately unscoped: `--group=<name>` alone, so every test of the group runs no matter
            // where it lives. ConfigurationFile scope is what keeps the path/filter flags out of the command line.
            selection.scope = TestoScope.CONFIGURATION_FILE
            selection.groups = groups
            selection.testoType = ""
            selection.dataProviderIndex = -1
            selection.dataSetIndex = -1
            return element
        }
        if (view is PhpAttributeView && php.view(view.owner) is PhpClassView) {
            // A class-level attribute (`#[Test]`, `#[TestRectorFixtures]`) runs the class it sits on, narrowed to the
            // kind of case the attribute declares. Running the class itself stays untyped and keeps everything the
            // class holds — that difference is the whole point of running from the attribute.
            val phpClass = view.owner!!
            if (!phpClass.isTestoClass()) return null
            setup(selection, phpClass, element.containingFile.virtualFile) ?: return null
            selection.testoType = testoTypeOf(element)
            return element
        }
        if (view is PhpAttributeView) {
            val function = view.owner?.takeIf { php.view(it) is PhpFunctionView } ?: return null
            setup(selection, function, element.containingFile.virtualFile) ?: return null
            val index = PsiUtil.getAttributeOrder(element, function)

            // `#[Test]` is runnable but NOT numbered (it has no attribute group), so
            // getAttributeOrder returns -1. In that case run the method as a plain test
            // (no `:index` suffix, no data-provider/dataset indices) instead of bailing out.
            // Numbered attributes (DataProvider/DataSet/.../TestInline/Bench) keep the `:index` suffix.
            if (index == -1) {
                selection.dataProviderIndex = -1
                selection.dataSetIndex = -1
                selection.testoType = testoTypeOf(element)

                return element
            }

            selection.methodName += ":$index"
            selection.dataProviderIndex = index
            selection.dataSetIndex = -1
            selection.testoType = testoTypeOf(element)

            return element
        }
        if (php.isYield(element)) {
            val function = enclosing(element) { php.view(it) is PhpFunctionView } ?: return null
            val datasetIndex = PsiUtil.getExitStatementOrder(element, function)
            if (datasetIndex == -1) return null

            val usages = TestoDataProviderUtils.findDataProviderUsages(function)
            if (usages.isEmpty()) return null

            // todo handle all [usages] with popup
            val usage = usages.first()
            setup(selection, usage, element.containingFile.virtualFile) ?: return null

            val dataProviderIndex = TestoDataProviderUtils.findDataProviderUsagesIndex(usage, function)

            selection.methodName += ":$dataProviderIndex:$datasetIndex"
            selection.dataProviderIndex = dataProviderIndex
            selection.dataSetIndex = datasetIndex

            return element
        }
        if (view is PhpClassView) {
            val testClass = findTestElement(element)?.takeIf { php.view(it) is PhpClassView } ?: return null

            /**
             * Classes are configured through the file, unfortunately.
             * But this should return a PhpClass not to be kicked out by Codeception precise target
             */
            val psiFile = testClass.containingFile

            // Deliberately untyped: running a class runs everything it holds. `--type=test` here would hide its
            // `#[Bench]` methods — narrowing by type is what running from an attribute is for.
            host.baseSetup(selection, psiFile, psiFile.virtualFile)
            return testClass
        }
        if (view is PhpFunctionView) {
            val found = findTestElement(element)
            if (found != null && php.view(found) is PhpFunctionView) {
                val usages = TestoDataProviderUtils.findDataProviderUsages(found)

                if (usages.isNotEmpty()) {
                    val target = usages.first()

                    return host.baseSetup(selection, target, found.containingFile.virtualFile)
                }
            }
        }
        if (element is PsiFile && php.isPhpFile(element) && element.isTestoConfigFile()) {
            selection.scope = TestoScope.CONFIGURATION_FILE
            selection.useAlternativeConfigurationFile = true
            selection.configurationFilePath = file.path
            return element
        }
        return host.baseSetup(selection, element, file)
    }

    fun matches(selection: TestoRunSelection, element: PsiElement): Boolean {
        val view = php.view(element)

        if (view is PhpClassReferenceView && view.newExpression != null && view.fqn == TestoClasses.APPLICATION_CONFIG) {
            return selection.scope == TestoScope.CONFIGURATION_FILE
                && selection.configurationFilePath == element.containingFile.virtualFile.path
        }
        if (view is PhpClassReferenceView && view.newExpression != null && view.fqn == TestoClasses.SUITE_CONFIG) {
            val suiteName = view.newExpression?.firstStringArgument ?: return false
            return selection.scope == TestoScope.CONFIGURATION_FILE
                && selection.configurationFilePath == element.containingFile.virtualFile.path
                && selection.suites == listOf(suiteName)
        }
        if (view is PhpAttributeView && view.fqn == TestoClasses.FILTER_GROUP) {
            val groups = groupNamesOf(view)
            return groups.isNotEmpty()
                && selection.scope == TestoScope.CONFIGURATION_FILE
                && selection.groups == groups
        }
        if (view is PhpAttributeView && php.view(view.owner) is PhpClassView) {
            // A class-level attribute configures its class narrowed to the attribute's own type, so only a
            // configuration of exactly that type is "this context" — an untyped one belongs to the class itself.
            return classMatches(selection, view.owner!!, testoTypeOf(element))
        }
        if (view is PhpClassView) {
            // Running the class itself is untyped; a typed configuration was produced from a class-level attribute
            // and must not be reused here — it would keep narrowing the run after the user asked for the whole class.
            return classMatches(selection, element, "")
        }
        if (view is PhpFunctionView) {
            val usages = TestoDataProviderUtils.findDataProviderUsages(element)

            if (usages.isNotEmpty()) {
                return false
            }
        }
        return host.baseMatches(selection, element)
    }

    private fun classMatches(selection: TestoRunSelection, phpClass: PsiElement, testoType: String): Boolean =
        selection.scope == TestoScope.FILE
            && selection.filePath == phpClass.containingFile.virtualFile.path
            && selection.testoType == testoType

    /**
     * Whether [selection] is the run of a results-tree node announced as [target]; null to leave it to the
     * element-based check. [element] is what the node's location resolved to.
     */
    fun treeNodeMatches(selection: TestoRunSelection, target: TestoRunTarget, element: PsiElement?): Boolean? {
        // A configuration built from source can look like "this context" while missing the selector the node was
        // announced with, and reusing it would run the whole file. The match is therefore exact on all three: a class
        // node and a method node of the same class agree on suite and type, so those alone are not enough.
        if (selection.suites != listOfNotNull(target.suite?.takeIf { it.isNotBlank() })) return false
        if (selection.testoType != target.type.orEmpty()) return false

        target.filter?.let {
            return selection.scope == TestoScope.METHOD && selection.methodName == it
        }

        // A hint naming no symbol — a config-file node announced with a suite. Should it resolve to a class anyway,
        // the element-based check applies, except that it expects an untyped run while this node carries a type.
        val found = element?.let { findTestElement(it) }
        if (found != null && php.view(found) is PhpClassView) return classMatches(selection, found, target.type.orEmpty())

        // A free function falls through untyped: its element-based check never compares testoType, so once Testo
        // sends `testType` a typed function configuration could pass for an untyped one — the mirror of the class
        // case above. Latent until then: without the attributes such a target is empty and never stored.
        return null
    }

    /** The element a run from [element] stands for, walking out from it; null where Testo runs nothing. */
    fun findTestElement(element: PsiElement?): PsiElement? {
        if (element == null || DumbService.getInstance(element.project).isDumb) return null

        val target = when (element) {
            is LeafPsiElement -> element.parent
            else -> element
        } ?: return null

        if (element is PsiDirectory) return element

        val psiFile = element.containingFile ?: return null
        if (host.isForeignTestFile(psiFile)) return null

        return candidate(target)
            ?: candidate(enclosingOrSelf(target) { php.view(it) is PhpAttributeView })
            ?: candidate(enclosingOrSelf(target) { php.view(it) is PhpFunctionView })
            ?: candidate(enclosingOrSelf(target) { php.view(it) is PhpClassView })
            ?: candidate(enclosingOrSelf(target) { it is PsiFile && php.isPhpFile(it) })
    }

    private fun candidate(target: PsiElement?): PsiElement? {
        if (target == null) return null
        val view = php.view(target)
        return when {
            view is PhpClassReferenceView -> target.takeIf {
                view.newExpression != null
                    && (view.fqn == TestoClasses.APPLICATION_CONFIG || view.fqn == TestoClasses.SUITE_CONFIG)
            }
            // `#[Group]` is runnable wherever it sits: it selects by group, not by location. Any other attribute needs
            // a runnable owner — a test/bench/provider function, or a Testo class. A class-level attribute is the
            // context itself, never a shortcut to its class: the attribute run carries its own type, the class run is
            // untyped.
            view is PhpAttributeView -> target.takeIf {
                if (view.fqn == TestoClasses.FILTER_GROUP) return@takeIf true
                val owner = view.owner ?: return@takeIf false
                owner.isTestoExecutable() || owner.isTestoDataProviderLike() || owner.isTestoClass()
            }
            view is PhpFunctionView -> target.takeIf { it.isTestoExecutable() || it.isTestoDataProviderLike() }
            view is PhpClassView -> target.takeIf { it.isTestoClass() }
            target is PsiFile && php.isPhpFile(target) -> target.takeIf { target.isTestoFile() }
            target is PsiDirectory -> target.takeIf { host.directoryHasPhpFiles(target) }
            php.isYield(target) -> target.takeIf {
                val method = enclosing(it) { parent -> (php.view(parent) as? PhpFunctionView)?.isMethod == true }
                method?.isTestoDataProviderLike() == true && TestoDataProviderUtils.isDataProvider(method)
            }
            else -> null
        }
    }

    companion object {
        const val TEST_TYPE = "test"
        const val INLINE_TYPE = "inline"
        const val BENCH_TYPE = "bench"

        /** The type the Rector bridge synthesizes for a rule's fixture case (`RectorFixtureInterceptor::TYPE`). */
        const val RECTOR_FIXTURE_TYPE = "rector-fixture"

        private val php: TestoPhp get() = TestoPhp.getInstance()

        /** The `--type` a run from [element] narrows to, or empty for none. */
        fun testoTypeOf(element: PsiElement): String {
            val attribute = php.view(element) as? PhpAttributeView
            return when {
                attribute != null -> testoTypeOfAttribute(attribute)
                element.isTestoBench() -> BENCH_TYPE
                element.isTestoFunction() && element.hasAttribute(TestoClasses.TEST_INLINE) -> INLINE_TYPE
                element.isTestoMethod() || element.isTestoFunction() -> TEST_TYPE
                else -> ""
            }
        }

        private fun testoTypeOfAttribute(attribute: PhpAttributeView): String {
            val fqn = attribute.fqn ?: return ""
            return when (fqn) {
                in TestoClasses.BENCH_ATTRIBUTES -> BENCH_TYPE
                TestoClasses.TEST_INLINE -> INLINE_TYPE
                TestoClasses.TEST -> TEST_TYPE
                TestoClasses.RECTOR_TEST_FIXTURES -> RECTOR_FIXTURE_TYPE
                in TestoClasses.DATA_ATTRIBUTES -> TEST_TYPE
                else -> ""
            }
        }

        /**
         * What the selected results-tree node was announced with, or null when the context is not a Testo run's tree.
         *
         * Both keys come from `TestTreeView.uiDataSnapshot`. The model ties the node back to its own run: the store is
         * per-run, and a target from another console would rerun the wrong thing. Identified by `nodeId` — see
         * [com.github.xepozz.testo.tests.console.TestoNodeIndex].
         */
        fun treeTarget(context: ConfigurationContext): TestoRunTarget? {
            val dataContext = context.dataContext
            val proxy = dataContext.getData(AbstractTestProxy.DATA_KEY) as? SMTestProxy ?: return null
            val model = dataContext.getData(TestTreeView.MODEL_DATA_KEY) ?: return null
            val properties = model.properties as? TestoConsoleProperties ?: return null

            return properties.targetStore.targetFor(proxy)
        }

        /** Lays what a results-tree node announced over the run its source element produced. */
        fun applyTreeTarget(selection: TestoRunSelection, target: TestoRunTarget) {
            target.suite?.takeIf { it.isNotBlank() }?.let { selection.suites = listOf(it) }
            target.type?.takeIf { it.isNotBlank() }?.let { selection.testoType = it }

            // The whole selector, class and all: a bare method name would also match a namesake in the same file, and
            // a case needs it too, since `--path` alone runs every case the file declares. `--path` stays as it was.
            val filter = target.filter ?: return
            if (selection.filePath.isNullOrEmpty()) return
            selection.scope = TestoScope.METHOD
            selection.methodName = filter
        }

        /**
         * A method keeps its selector with the inheritor prepended (`\Ns\Test::foo`, a valid `--filter` shape) —
         * the inheritor's file path alone would run everything that file holds.
         */
        fun applyInheritorChoice(selection: TestoRunSelection, testTarget: PsiElement, inheritor: PsiElement) {
            val inheritorClass = php.view(inheritor) as PhpClassView
            selection.filePath = inheritor.containingFile.virtualFile.presentableUrl
            val selector = selection.methodName
            if ((php.view(testTarget) as? PhpFunctionView)?.isMethod == true && !selector.isNullOrEmpty()) {
                selection.scope = TestoScope.METHOD
                selection.methodName = "${inheritorClass.fqn}::$selector"
            } else {
                selection.scope = TestoScope.FILE
            }
        }

        /** Narrows a run to one test fed by a data provider: the provider's [index] and, when known, a data set. */
        fun applyDataSetUsage(selection: TestoRunSelection, test: PsiElement, index: Int, datasetIndex: Int) {
            val name = (php.view(test) as PhpFunctionView).name
            selection.scope = TestoScope.METHOD
            selection.filePath = test.containingFile.virtualFile.presentableUrl

            if (datasetIndex > -1) {
                selection.methodName = "$name:$index:$datasetIndex"
            } else {
                selection.methodName = "$name:$index"
            }
            selection.dataProviderIndex = index
            selection.dataSetIndex = datasetIndex
        }

        private fun enclosing(element: PsiElement, predicate: (PsiElement) -> Boolean): PsiElement? =
            element.parent?.let { enclosingOrSelf(it, predicate) }

        /** [element] or its nearest ancestor within the same file satisfying [predicate], like `parentOfType(withSelf)`. */
        private fun enclosingOrSelf(element: PsiElement, predicate: (PsiElement) -> Boolean): PsiElement? {
            var current: PsiElement? = element
            while (current != null) {
                if (predicate(current)) return current
                if (current is PsiFile) return null
                current = current.parent
            }
            return null
        }
    }
}
