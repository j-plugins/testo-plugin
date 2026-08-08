package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.TestoUtil
import com.github.xepozz.testo.hasAttribute
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoBench
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoConfigFile
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.isTestoFunction
import com.github.xepozz.testo.isTestoMethod
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.util.Consumer
import com.intellij.util.asSafely
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.PhpIndexImpl
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.PhpYield
import com.jetbrains.php.lang.psi.stubs.indexes.expectedArguments.PhpExpectedFunctionScalarArgument
import com.jetbrains.php.phpunit.PhpMethodLocation
import com.jetbrains.php.phpunit.PhpUnitRuntimeConfigurationProducer
import com.jetbrains.php.phpunit.PhpUnitUtil
import com.jetbrains.php.testFramework.run.PhpTestConfigurationProducer
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings
import java.util.*
import javax.swing.ListSelectionModel

class TestoRunConfigurationProducer : PhpTestConfigurationProducer<TestoRunConfiguration>(
    TestoTestRunnerSettingsValidator,
    FILE_TO_SCOPE,
    METHOD_NAMER,
    METHOD,
) {
    override fun isEnabled(project: Project) = TestoUtil.isEnabled(project)

    /**
     * Right-clicking a node of the results tree, rather than a piece of source.
     *
     * The PSI a location hint resolves to is lossy — a data set has no method of its own to find, so it lands on its
     * class and the run widens to the whole file — and the `testSuite` / `testType` a node may have been announced
     * with are not in the PSI at all. So whatever the element-based path produced is corrected here with what the
     * node's own service message said. See [TestoRunTarget].
     *
     * Only a node the platform could resolve to a [Location] gets here at all: `PreferredProducerFind` runs no
     * producer without one. That covers cases, tests, DataProvider batches and data sets — every node Testo points at
     * code — but not a run-level suite, which is a configuration entry with no location hint of its own.
     */
    override fun setupConfigurationFromContext(
        configuration: TestoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = treeTarget(context)
        if (!super.setupConfigurationFromContext(configuration, context, sourceElement)) return false
        if (target == null) return true

        applyTreeTarget(configuration.testoSettings.getTestoRunnerSettings(), target)
        configuration.name = configuration.suggestedName()
        return true
    }

    override fun isConfigurationFromContext(
        configuration: TestoRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        // A configuration built from source can look like "this context" to the element-based check while missing the
        // very selector the node was announced with — reusing it would silently run the whole file instead of the one
        // data set. When the context is a tree node, only a configuration that spells the node out counts, and
        // nothing beyond it: a class node and a method node of the same class agree on suite and type, so matching
        // on those alone would hand the class the method's configuration.
        val target = treeTarget(context) ?: return super.isConfigurationFromContext(configuration, context)
        val settings = configuration.testoSettings.getTestoRunnerSettings()

        if (settings.suite != target.suite.orEmpty()) return false
        if (settings.testoType != target.type.orEmpty()) return false

        target.filter?.let {
            return settings.scope == PhpTestRunnerSettings.Scope.Method && settings.methodName == it
        }

        // A hint that names no symbol at all — a file, i.e. a config-file node announced with a suite. Should it still
        // resolve to a class, the element-based check is the right comparison, save that it expects an untyped class
        // run while this node's own type is what the configuration was given.
        val element = context.psiLocation?.let { findTestElement(it, getWorkingDirectory(it)) }
        if (element is PhpClass) return isClassConfigurationFromContext(settings, element, target.type.orEmpty())

        // A free test function falls through untyped: its element-based check never compares testoType, so once
        // Testo starts sending `testType` a typed function configuration could be confused with an untyped one —
        // the mirror of the class problem solved above. Latent until then: a function node without the attributes
        // has an empty target and never reaches this method at all.
        return super.isConfigurationFromContext(configuration, context)
    }

    /**
     * What the selected results-tree node was announced with, or null when the context is not a Testo run's tree.
     *
     * Both keys are filled eagerly by `TestTreeView.uiDataSnapshot`, so they are in the snapshot the context carries
     * and reading them costs nothing. The model is what ties the node back to the run it belongs to — the store is
     * per-run, and a stale target from another console would rerun the wrong thing.
     *
     * The node is identified by its `locationUrl`, which is the `locationHint` its message carried, verbatim: the
     * name would not do, since every data provider in a run opens a `Dataset #0 [0]` of its own.
     */
    private fun treeTarget(context: ConfigurationContext): TestoRunTarget? {
        val dataContext = context.dataContext
        val proxy = dataContext.getData(AbstractTestProxy.DATA_KEY) as? SMTestProxy ?: return null
        val model = dataContext.getData(TestTreeView.MODEL_DATA_KEY) ?: return null
        val properties = model.properties as? TestoConsoleProperties ?: return null

        return properties.targetStore.targetFor(proxy.locationUrl)
    }

    private fun applyTreeTarget(settings: TestoRunnerSettings, target: TestoRunTarget) {
        target.suite?.takeIf { it.isNotBlank() }?.let { settings.suite = it }
        target.type?.takeIf { it.isNotBlank() }?.let { settings.testoType = it }

        // The whole selector goes into `--filter`, class and all: `--filter "\Ns\Calculator::med:3:0"` picks exactly
        // the node, where the bare method name would also match a namesake elsewhere in the file. `--path` stays as
        // the element-based path set it, so the run still only reads the one file.
        //
        // A case selector goes in too, even though the element-based path already found the right file: a file may
        // declare several cases, and `--path` alone would run every one of them.
        val filter = target.filter ?: return
        if (settings.filePath.isNullOrEmpty()) return
        settings.scope = PhpTestRunnerSettings.Scope.Method
        settings.methodName = filter
    }

    public override fun setupConfiguration(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement,
        virtualFile: VirtualFile
    ): PsiElement? {
        val testRunnerSettings = testRunnerSettings as TestoRunnerSettings

        if (element is ClassReference && element.parent is NewExpression && element.fqn == TestoClasses.APPLICATION_CONFIG) {
            testRunnerSettings.scope = PhpTestRunnerSettings.Scope.ConfigurationFile
            testRunnerSettings.isUseAlternativeConfigurationFile = true
            testRunnerSettings.configurationFilePath = virtualFile.path
            return element
        }
        if (element is ClassReference && element.parent is NewExpression && element.fqn == TestoClasses.SUITE_CONFIG) {
            val newExpression = element.parent as NewExpression
            val suiteName = extractSuiteName(newExpression) ?: return null
            testRunnerSettings.scope = PhpTestRunnerSettings.Scope.ConfigurationFile
            testRunnerSettings.isUseAlternativeConfigurationFile = true
            testRunnerSettings.configurationFilePath = virtualFile.path
            testRunnerSettings.suite = suiteName
            return element
        }
        if (element is PhpAttribute && element.fqn == TestoClasses.FILTER_GROUP) {
            val groups = extractGroupNames(element)
            if (groups.isEmpty()) return null

            // A group run is deliberately unscoped: `--group=<name>` alone, so every test of the group runs no matter
            // where it lives. ConfigurationFile scope is what keeps the path/filter flags out of the command line.
            testRunnerSettings.scope = PhpTestRunnerSettings.Scope.ConfigurationFile
            testRunnerSettings.groups = groups.toMutableList()
            testRunnerSettings.testoType = ""
            testRunnerSettings.dataProviderIndex = -1
            testRunnerSettings.dataSetIndex = -1
            return element
        }
        if (element is PhpAttribute && element.owner is PhpClass) {
            // A class-level attribute (`#[Test]`, `#[TestRectorFixtures]`) runs the class it sits on, narrowed to the
            // kind of case the attribute declares. Running the class itself stays untyped and keeps everything the
            // class holds — that difference is the whole point of running from the attribute.
            val phpClass = element.owner as PhpClass
            if (!phpClass.isTestoClass()) return null
            setupConfiguration(testRunnerSettings, phpClass, element.containingFile.virtualFile) ?: return null
            testRunnerSettings.testoType = resolveTestoType(element)
            return element
        }
        if (element is PhpAttribute) {
            val function = element.owner as? Function ?: return null
            setupConfiguration(testRunnerSettings, function, element.containingFile.virtualFile) ?: return null
            val index = PsiUtil.getAttributeOrder(element, function)

            // `#[Test]` is runnable but NOT numbered (it has no attribute group), so
            // getAttributeOrder returns -1. In that case run the method as a plain test
            // (no `:index` suffix, no data-provider/dataset indices) instead of bailing out.
            // Numbered attributes (DataProvider/DataSet/.../TestInline/Bench) keep the `:index` suffix.
            if (index == -1) {
                testRunnerSettings.dataProviderIndex = -1
                testRunnerSettings.dataSetIndex = -1
                testRunnerSettings.testoType = resolveTestoType(element)

                return element
            }

            testRunnerSettings.methodName += ":$index"
            testRunnerSettings.dataProviderIndex = index
            testRunnerSettings.dataSetIndex = -1
            testRunnerSettings.testoType = resolveTestoType(element)

            return element
        }
        if (element is PhpYield) {
            val function = element.parentOfType<Function>() ?: return null
            val datasetIndex = PsiUtil.getExitStatementOrder(element, function)
            if (datasetIndex == -1) return null

            val usages = TestoDataProviderUtils.findDataProviderUsages(function)
            if (usages.isEmpty()) return null

            // todo handle all [usages] with popup
            val usage = usages.first()
            setupConfiguration(testRunnerSettings, usage, element.containingFile.virtualFile) ?: return null

            val dataProviderIndex = TestoDataProviderUtils.findDataProviderUsagesIndex(usage, function)

            testRunnerSettings.methodName += ":$dataProviderIndex:$datasetIndex"
            testRunnerSettings.dataProviderIndex = dataProviderIndex
            testRunnerSettings.dataSetIndex = datasetIndex

            return element
        }
        if (element is PhpClass) {
            val element = findTestElement(element, getWorkingDirectory(element)) as? PhpClass ?: return null

            /**
             * Classes are configured through the file, unfortunately.
             * But this should return a PhpClass not to be kicked out by Codeception precise target
             */
            val psiFile = element.containingFile

            // Deliberately untyped: running a class runs everything it holds. `--type=test` here would hide its
            // `#[Bench]` methods — narrowing by type is what running from an attribute is for.
            super.setupConfiguration(testRunnerSettings, psiFile, psiFile.virtualFile)
            return element
        }
        if (element is Function) {
            val element = findTestElement(element, getWorkingDirectory(element))
            if (element is Function) {
                val usages = TestoDataProviderUtils.findDataProviderUsages(element)

                if (usages.isNotEmpty()) {
                    val target = usages.first()

                    return super.setupConfiguration(testRunnerSettings, target, element.containingFile.virtualFile)
                }
            }
        }
        if (element is PhpFile && element.isTestoConfigFile()) {
            testRunnerSettings.scope = PhpTestRunnerSettings.Scope.ConfigurationFile
            testRunnerSettings.isUseAlternativeConfigurationFile = true
            testRunnerSettings.configurationFilePath = virtualFile.path
            return element
        }
        val result = super.setupConfiguration(testRunnerSettings, element, virtualFile)
        return result
    }

    public override fun isConfigurationFromContext(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement
    ): Boolean {
        if (element is ClassReference && element.parent is NewExpression && element.fqn == TestoClasses.APPLICATION_CONFIG) {
            return testRunnerSettings.scope == PhpTestRunnerSettings.Scope.ConfigurationFile
                && testRunnerSettings.configurationFilePath == element.containingFile.virtualFile.path
        }
        if (element is ClassReference && element.parent is NewExpression && element.fqn == TestoClasses.SUITE_CONFIG) {
            val testoSettings = testRunnerSettings as? TestoRunnerSettings ?: return false
            val newExpression = element.parent as NewExpression
            val suiteName = extractSuiteName(newExpression) ?: return false
            return testoSettings.scope == PhpTestRunnerSettings.Scope.ConfigurationFile
                && testoSettings.configurationFilePath == element.containingFile.virtualFile.path
                && testoSettings.suite == suiteName
        }
        if (element is PhpAttribute && element.fqn == TestoClasses.FILTER_GROUP) {
            val testoSettings = testRunnerSettings as? TestoRunnerSettings ?: return false
            val groups = extractGroupNames(element)
            return groups.isNotEmpty()
                && testoSettings.scope == PhpTestRunnerSettings.Scope.ConfigurationFile
                && testoSettings.groups == groups
        }
        if (element is PhpAttribute && element.owner is PhpClass) {
            // A class-level attribute configures its class narrowed to the attribute's own type, so only a
            // configuration of exactly that type is "this context" — an untyped one belongs to the class itself.
            return isClassConfigurationFromContext(testRunnerSettings, element.owner as PhpClass, resolveTestoType(element))
        }
        if (element is PhpClass) {
            // Running the class itself is untyped; a typed configuration was produced from a class-level attribute
            // and must not be reused here — it would keep narrowing the run after the user asked for the whole class.
            return isClassConfigurationFromContext(testRunnerSettings, element, "")
        }
        if (element is Function) {
            val usages = TestoDataProviderUtils.findDataProviderUsages(element)

            if (usages.isNotEmpty()) {
                return false
            }
        }
        return super.isConfigurationFromContext(testRunnerSettings, element)
    }

    private fun isClassConfigurationFromContext(
        testRunnerSettings: PhpTestRunnerSettings,
        phpClass: PhpClass,
        testoType: String,
    ): Boolean = testRunnerSettings.scope == PhpTestRunnerSettings.Scope.File
        && testRunnerSettings.filePath == phpClass.containingFile.virtualFile.path
        && (testRunnerSettings as? TestoRunnerSettings)?.testoType == testoType

    override fun getWorkingDirectory(element: PsiElement): VirtualFile? {
        if (element is PsiDirectory) {
            return element.parentDirectory?.virtualFile
        }

        return element.containingFile?.containingDirectory?.virtualFile
    }

    override fun getConfigurationFactory() = TestoRunConfigurationFactory(TestoRunConfigurationType.INSTANCE)

    override fun shouldReplace(self: ConfigurationFromContext, other: ConfigurationFromContext) = false

    override fun onFirstRun(
        configuration: ConfigurationFromContext,
        context: ConfigurationContext,
        startRunnable: Runnable
    ) {
        // The choosers below exist to resolve what a source element leaves open — which subclass of an abstract case,
        // which test a data provider feeds. A tree node has none of that open: it is one node of a run that already
        // happened, and its selector names the concrete class outright. Asking again would only offer a chance to
        // rerun something else.
        if (treeTarget(context) != null) {
            startRunnable.run()
            return
        }

        val testoRunConfiguration = configuration.configuration as TestoRunConfiguration
        val testRunnerSettings = testoRunConfiguration.testoSettings.runnerSettings
        val location = context.location
        if (location is PsiLocation<*>) {
            val psiElement = location.psiElement
            val element = findTestElement(psiElement, getWorkingDirectory(psiElement))

            // A class-level attribute is the class run in disguise (narrowed by type), so it must go through the
            // same abstract-class inheritor chooser as the class itself. `#[Group]` stays out — a group run does
            // not need a concrete class at all.
            val classTarget = when {
                element is PhpClass -> element
                element is PhpAttribute && element.fqn != TestoClasses.FILTER_GROUP -> element.owner as? PhpClass
                else -> null
            }
            if (classTarget != null) {
                if (tryRunAbstract(
                        classTarget,
                        context.dataContext,
                        testRunnerSettings,
                        startRunnable,
                        testoRunConfiguration,
                        location
                    )
                ) {
                    return
                }
            }

            if (element is Method && element.containingClass?.isAbstract == true) {
                if (tryRunAbstract(
                        element,
                        context.dataContext,
                        testRunnerSettings,
                        startRunnable,
                        testoRunConfiguration,
                        location
                    )
                ) {
                    return
                }
            }

            if (element is PhpYield) {
                val function = element.parentOfType<Function>() ?: return
                val datasetIndex = PsiUtil.getExitStatementOrder(element, function)

                if (onFirstRunOnFunction(
                        function,
                        context,
                        testRunnerSettings,
                        startRunnable,
                        testoRunConfiguration,
                        datasetIndex,
                    )
                ) return
            }

            if (element is Function) {
                if (onFirstRunOnFunction(
                        element,
                        context,
                        testRunnerSettings,
                        startRunnable,
                        testoRunConfiguration,
                        -1,
                    )
                ) return
            }
        }

        super.onFirstRun(configuration, context, startRunnable)
    }

    private fun onFirstRunOnFunction(
        function: Function,
        context: ConfigurationContext,
        testRunnerSettings: TestoRunnerSettings,
        startRunnable: Runnable,
        testoRunConfiguration: TestoRunConfiguration,
        datasetIndex: Int,
    ): Boolean {
        if (!function.isTestoDataProviderLike()) return false

        val dataSetUsages = TestoDataProviderUtils.findDataProviderUsages(function)
        //                println("dataSetUsages: $dataSetUsages for dataSet: $element")
        if (dataSetUsages.size > 1) {
            showDataSetUsageChooser(
                function,
                dataSetUsages,
                context,
                testRunnerSettings,
                startRunnable,
                testoRunConfiguration,
                datasetIndex,
            )
            return true
        }

        //            if (tryRunAbstract(
        //                    element,
        //                    context.dataContext,
        //                    testRunnerSettings,
        //                    startRunnable,
        //                    testoRunConfiguration,
        //                    location
        //                )
        //            ) {
        //                return
        //            }
        return false
    }

    override fun findTestElement(element: PsiElement?, workingDirectory: VirtualFile?): PsiElement? {
        if (element == null || DumbService.getInstance(element.project).isDumb) return null

        val target = when (element) {
            is LeafPsiElement -> element.parent
            else -> element
        } ?: return null

        if (element is PsiDirectory) return element

        val psiFile = element.containingFile ?: return null
        if (PhpUnitUtil.isPhpUnitTestFile(psiFile)) return null

        return findTestElement(target)
            ?: findTestElement(target.parentOfType<PhpAttribute>(true))
            ?: findTestElement(target.parentOfType<Function>(true))
            ?: findTestElement(target.parentOfType<PhpClass>(true))
            ?: findTestElement(target.parentOfType<PhpFile>(true))
    }

    private fun findTestElement(target: PsiElement?): PsiElement? = when (target) {
        is ClassReference -> target.takeIf { it.parent is NewExpression && (it.fqn == TestoClasses.APPLICATION_CONFIG || it.fqn == TestoClasses.SUITE_CONFIG) }
        // `#[Group]` is runnable wherever it sits: it selects by group, not by location. Any other attribute needs a
        // runnable owner — a test/bench/provider function, or a Testo class. A class-level attribute is the context
        // itself, never a shortcut to its class: the attribute run carries its own type, the class run is untyped.
        is PhpAttribute -> target.takeIf {
            if (it.fqn == TestoClasses.FILTER_GROUP) return@takeIf true
            val owner = it.owner ?: return@takeIf false
            owner.isTestoExecutable() || owner.isTestoDataProviderLike() || owner.isTestoClass()
        }
        is Function -> target.takeIf { it.isTestoExecutable() || it.isTestoDataProviderLike() }
        is PhpClass -> target.takeIf { it.isTestoClass() }
        is PhpFile -> target.takeIf { it.isTestoFile() }
        is PsiDirectory -> target.takeIf {
            PhpUnitRuntimeConfigurationProducer.checkDirectoryContainsPhpFiles(
                target.virtualFile,
                target.project
            )
        }

        is PhpYield -> target.takeIf {
            val method = it.parentOfType<Method>()
            method?.isTestoDataProviderLike() == true && TestoDataProviderUtils.isDataProvider(method)
        }

        else -> null
    }

    private fun tryRunAbstract(
        testTarget: PhpNamedElement?,
        context: DataContext,
        testRunnerSettings: TestoRunnerSettings,
        startRunnable: Runnable,
        configuration: TestoRunConfiguration,
        location: Location<*>
    ): Boolean {
        val testClass = when (testTarget) {
            is PhpClass -> testTarget
            is Method -> getContainingClass(location, testTarget)
            else -> null
        } ?: return false

        if (testClass.isAbstract) {
            val testSubClasses =
                (PhpIndex.getInstance(testClass.project) as PhpIndexImpl).getAllSubclasses(testClass.fqn)
                    .filter { it.isTestoClass() }
//            if (testSubClasses.size > 1) {
            showInheritorChooses(
                testTarget!!,
                context,
                testRunnerSettings,
                startRunnable,
                configuration,
                location,
                testSubClasses
            )
            return true
//            }

//            if (testSubClasses.size == 1) {
//                configureByAbstractClass(
//                    testTarget!!,
//                    testRunnerSettings,
//                    startRunnable,
//                    configuration,
//                    location,
//                    testSubClasses.get(0) as PhpClass?
//                )
//                updateNameAndRun(configuration, startRunnable)
//                return true
//            }
        }

        return false
    }

    private fun showInheritorChooses(
        testTarget: PhpNamedElement,
        context: DataContext,
        testRunnerSettings: TestoRunnerSettings,
        startRunnable: Runnable,
        configuration: TestoRunConfiguration,
        location: Location<*>,
        testSubClasses: Collection<PhpClass>
    ) {

        val name = testTarget.name
        val callback = getRunInheritorsCallback(
            testTarget,
            testRunnerSettings,
            startRunnable,
            configuration,
            location,
            testSubClasses,
            name
        )
        createChooserPopup(
            testSubClasses,
            PhpBundle.message("choose.executable.class.to.run.0", *arrayOf<Any>(name)),
            false,
            callback
        ).showInBestPositionFor(context)
    }

    private fun showDataSetUsageChooser(
        dataSet: Function,
        dataSetUsages: Collection<Method>,
        context: ConfigurationContext,
        testRunnerSettings: TestoRunnerSettings,
        startRunnable: Runnable,
        configuration: TestoRunConfiguration,
        datasetIndex: Int,
    ) {
        val callback = getRunDataSetUsagesCallback(
            testRunnerSettings,
            startRunnable,
            configuration,
            dataSetUsages,
            dataSet,
            datasetIndex,
        )
        createChooserPopup(
            dataSetUsages,
            PhpBundle.message("choose.test.method.to.run.dataset.0", dataSet.name),
            true,
            callback,
        ).showInBestPositionFor(context.dataContext)
    }

    private fun createChooserPopup(
        elements: Collection<PsiElement>,
        title: String,
        showMethodName: Boolean,
        callback: Consumer<Set<*>>
    ): JBPopup {
        val jbListItems = mutableListOf<PsiElement?>(*elements.toTypedArray())
        // todo: use ListSelectionModel.MULTIPLE_INTERVAL_SELECTION when add All option
//        jbListItems.add(0, null)

        return JBPopupFactory.getInstance()
            .createPopupChooserBuilder(jbListItems)
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setRenderer(
                com.github.xepozz.testo.tests.overrides.PhpRunInheritorsListCellRenderer(
                    elements.size,
                    showMethodName
                )
            )
            .setTitle(title)
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .setItemsChosenCallback(callback)
            .createPopup()
    }

    private fun getRunDataSetUsagesCallback(
        testRunnerSettings: TestoRunnerSettings,
        startRunnable: Runnable,
        configuration: TestoRunConfiguration,
        values: Collection<Method>,
        dataProvider: Function,
        datasetIndex: Int,
    ): Consumer<Set<*>> {
        return Consumer { selectedValues: Set<*> ->
            val function = selectedValues.firstOrNull() as? Function ?: return@Consumer
            val index = TestoDataProviderUtils.findDataProviderUsagesIndex(function, dataProvider)

//            setupConfiguration(testRunnerSettings, function, function.containingFile.virtualFile) ?: return@Consumer
//            val index = PsiUtil.getAttributeOrder(attribute, function)
//            if (index == -1) return@Consumer

            testRunnerSettings.scope = PhpTestRunnerSettings.Scope.Method
            testRunnerSettings.filePath = function.containingFile.virtualFile.presentableUrl

            if (datasetIndex > -1) {
                testRunnerSettings.methodName = function.name + ":$index:$datasetIndex"
            } else {
                testRunnerSettings.methodName = function.name + ":$index"
            }
            testRunnerSettings.dataProviderIndex = index
            testRunnerSettings.dataSetIndex = datasetIndex

            configuration.name = configuration.suggestedName()

            startRunnable.run()
        }
    }
//
//    private fun getRunDataSetUsagesCallback(
//        testRunnerSettings: TestoRunnerSettings,
//        startRunnable: Runnable,
//        configuration: TestoRunConfiguration,
//        values: MutableList<Method>,
//        dataSetName: String
//    ): Consumer<MutableSet<*>?> {
//        return Consumer { selectedValues: MutableSet<*>? ->
//            val valuesToRun = (if (ContainerUtil.exists(
//                    selectedValues,
//                    { obj: Any? -> Objects.isNull(obj) })
//            ) values else selectedValues) as MutableCollection<*>
//            PhpUnitRuntimeConfigurationProducer.configurePattern(
//                testRunnerSettings, PhpUnitRuntimeConfigurationProducer.buildPatterns(
//                    StreamEx.of(valuesToRun).select<Method?>(
//                        Method::class.java
//                    ) as Stream<*>?, dataSetName
//                )
//            )
//            PhpUnitRuntimeConfigurationProducer.updateNameAndRun(configuration, startRunnable)
//        }
//    }

    private fun getRunInheritorsCallback(
        testTarget: PhpNamedElement,
        testRunnerSettings: TestoRunnerSettings,
        startRunnable: Runnable,
        configuration: TestoRunConfiguration,
        location: Location<*>,
        testSubClasses: Collection<PhpClass>,
        targetName: String
    ): Consumer<Set<*>> {
        return Consumer { selectedValues: Set<*> ->
            val valuesToRun = when {
                selectedValues.any { Objects.isNull(it) } -> testSubClasses
                else -> selectedValues.filterIsInstance<PhpClass>()
            }
            if (valuesToRun.size == 1) {
                testRunnerSettings.scope = PhpTestRunnerSettings.Scope.File
                testRunnerSettings.filePath = valuesToRun.first().containingFile.virtualFile.presentableUrl
            } else {
//                var testPatterns = when (testTarget) {
//                    is PhpClass -> valuesToRun.map{  PhpUnitTestPattern.create(it) }
//
//                    else -> mutableListOf<PhpUnitTestPattern>()
//                }
//                if (testTarget is Method) {
//                    testPatterns = SmartList()
//
//                    for (phpClass in valuesToRun) {
//                        val path = phpClass.getContainingFile().getVirtualFile().getPath()
//                        testPatterns.add(PhpUnitTestPattern(phpClass.getPresentableFQN(), targetName, path))
//                    }
//                }
//
//                PhpUnitRuntimeConfigurationProducer.configurePattern(testRunnerSettings, testPatterns)
            }
            configuration.name = configuration.suggestedName()
            startRunnable.run()
        }
    }

    private fun extractSuiteName(newExpression: NewExpression): String? {
        return newExpression
            .parameters
            .firstOrNull()
            ?.asSafely<StringLiteralExpression>()
            ?.contents
    }

    private fun getContainingClass(location: Location<*>, method: Method) = when (location) {
        is PhpMethodLocation -> location.containingClass
        else -> method.containingClass
    }

    companion object Companion {
        const val TEST_TYPE = "test"
        const val INLINE_TYPE = "inline"
        const val BENCH_TYPE = "bench"

        /** The type the Rector bridge synthesizes for a rule's fixture case (`RectorFixtureInterceptor::TYPE`). */
        const val RECTOR_FIXTURE_TYPE = "rector-fixture"

        fun resolveTestoType(element: PsiElement): String = when {
            element is PhpAttribute -> resolveTestoTypeFromAttribute(element)
            element.isTestoBench() -> BENCH_TYPE
            element.isTestoFunction() && (element as Function).hasAttribute(TestoClasses.TEST_INLINE) -> INLINE_TYPE
            element.isTestoMethod() || element.isTestoFunction() -> TEST_TYPE
            else -> ""
        }

        private fun resolveTestoTypeFromAttribute(attribute: PhpAttribute): String {
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
         * The group names of a `#[Group('db', 'slow')]` attribute, in source order. Read through the expected-argument
         * API (the same one [com.github.xepozz.testo.index.TestoDataProvidersIndex] uses) so it also works on stubs;
         * non-literal arguments (constants, concatenations) cannot be resolved here and are skipped.
         */
        fun extractGroupNames(attribute: PhpAttribute): List<String> = attribute.arguments
            .mapNotNull { it.argument as? PhpExpectedFunctionScalarArgument }
            .filter { it.isStringLiteral }
            .map { StringUtil.unquoteString(it.value) }
            .filter { it.isNotBlank() }

        val METHOD = Condition<PsiElement> {
            it.isTestoExecutable() || (it is Method && TestoDataProviderUtils.isDataProvider(it))
        }
        private val METHOD_NAMER = { element: PsiElement? -> (element as? PhpNamedElement)?.name }
        private val FILE_TO_SCOPE = { file: PsiFile? ->
            file
                ?.takeIf { it.isTestoFile() }
//                .apply { println("file to scope: $file -> $this") }
        }
    }
}
