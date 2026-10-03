package com.github.xepozz.testo.phpstorm.tests.run

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.TestoUtil
import com.github.xepozz.testo.groupNamesOf
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.launch.TestoRunContexts
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.phpstorm.load
import com.github.xepozz.testo.phpstorm.toSelection
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import com.intellij.util.Consumer
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.PhpIndexImpl
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpYield
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
     * The PSI a location hint resolves to is lossy — a data set lands on its class and the run widens to the file —
     * and `testSuite`/`testType` are not in the PSI at all, so the element-based result is corrected here with what
     * the node's own message said. See [TestoRunTarget].
     *
     * Only a node the platform resolved to a [Location] gets here: `PreferredProducerFind` runs no producer without
     * one, which leaves out a run-level suite.
     */
    override fun setupConfigurationFromContext(
        configuration: TestoRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = TestoRunContexts.treeTarget(context)
        if (!super.setupConfigurationFromContext(configuration, context, sourceElement)) return false
        if (target == null) return true

        val settings = configuration.testoSettings.getTestoRunnerSettings()
        val selection = settings.toSelection()
        TestoRunContexts.applyTreeTarget(selection, target)
        settings.load(selection)
        configuration.name = configuration.suggestedName()
        return true
    }

    override fun isConfigurationFromContext(
        configuration: TestoRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val target = TestoRunContexts.treeTarget(context) ?: return super.isConfigurationFromContext(configuration, context)
        val settings = configuration.testoSettings.getTestoRunnerSettings()

        return contexts(settings).treeNodeMatches(settings.toSelection(), target, context.psiLocation)
            ?: super.isConfigurationFromContext(configuration, context)
    }

    public override fun setupConfiguration(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement,
        virtualFile: VirtualFile
    ): PsiElement? {
        val settings = testRunnerSettings as TestoRunnerSettings
        val selection = settings.toSelection()
        val result = contexts(settings).setup(selection, element, virtualFile)
        settings.load(selection)
        return result
    }

    public override fun isConfigurationFromContext(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement
    ): Boolean {
        val settings = testRunnerSettings as? TestoRunnerSettings ?: return false
        return contexts(settings).matches(settings.toSelection(), element)
    }

    /**
     * Testo's decisions (see [TestoRunContexts]) on top of PhpStorm's test producer, which answers where Testo has no
     * rule of its own. [settings] is what the base producer reads and writes; null where nothing is set up.
     */
    private fun contexts(settings: TestoRunnerSettings?) = TestoRunContexts(object : TestoRunContexts.Host {
        override fun baseSetup(selection: TestoRunSelection, element: PsiElement, file: VirtualFile): PsiElement? {
            val target = checkNotNull(settings) { "No settings to set up" }
            target.load(selection)
            val result = baseSetupConfiguration(target, element, file)
            selection.assign(target.toSelection())
            return result
        }

        override fun baseMatches(selection: TestoRunSelection, element: PsiElement): Boolean {
            val target = checkNotNull(settings) { "No settings to compare" }
            return baseIsConfigurationFromContext(target, element)
        }

        override fun isForeignTestFile(file: PsiFile) = PhpUnitUtil.isPhpUnitTestFile(file)

        override fun directoryHasPhpFiles(directory: PsiDirectory) =
            PhpUnitRuntimeConfigurationProducer.checkDirectoryContainsPhpFiles(directory.virtualFile, directory.project)
    })

    private fun baseSetupConfiguration(settings: PhpTestRunnerSettings, element: PsiElement, file: VirtualFile) =
        super.setupConfiguration(settings, element, file)

    private fun baseIsConfigurationFromContext(settings: PhpTestRunnerSettings, element: PsiElement) =
        super.isConfigurationFromContext(settings, element)

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
        if (TestoRunContexts.treeTarget(context) != null) {
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

            // `#[Group]` stays out of the inheritor chooser — a group run needs no concrete class.
            val methodTarget = element as? Method
                ?: (element as? PhpAttribute)?.takeIf { it.fqn != TestoClasses.FILTER_GROUP }?.owner as? Method
            if (methodTarget != null && methodTarget.containingClass?.isAbstract == true) {
                if (tryRunAbstract(
                        methodTarget,
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

        val dataSetUsages = TestoDataProviderUtils.findDataProviderUsages(function).filterIsInstance<Method>()
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

    override fun findTestElement(element: PsiElement?, workingDirectory: VirtualFile?): PsiElement? =
        contexts(null).findTestElement(element)

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
                com.github.xepozz.testo.phpstorm.tests.overrides.PhpRunInheritorsListCellRenderer(
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

            val selection = testRunnerSettings.toSelection()
            TestoRunContexts.applyDataSetUsage(selection, function, index, datasetIndex)
            testRunnerSettings.load(selection)

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
                applyInheritorChoice(testRunnerSettings, testTarget, valuesToRun.first())
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

    private fun getContainingClass(location: Location<*>, method: Method) = when (location) {
        is PhpMethodLocation -> location.containingClass
        else -> method.containingClass
    }

    companion object Companion {
        const val TEST_TYPE = TestoRunContexts.TEST_TYPE
        const val INLINE_TYPE = TestoRunContexts.INLINE_TYPE
        const val BENCH_TYPE = TestoRunContexts.BENCH_TYPE

        /** The type the Rector bridge synthesizes for a rule's fixture case (`RectorFixtureInterceptor::TYPE`). */
        const val RECTOR_FIXTURE_TYPE = TestoRunContexts.RECTOR_FIXTURE_TYPE

        fun resolveTestoType(element: PsiElement): String = TestoRunContexts.testoTypeOf(element)

        /** The group names of a `#[Group('db', 'slow')]` attribute — see [groupNamesOf]. */
        fun extractGroupNames(attribute: PhpAttribute): List<String> =
            groupNamesOf(TestoPhp.getInstance().view(attribute) as PhpAttributeView)

        /** See [TestoRunContexts.applyInheritorChoice]. */
        internal fun applyInheritorChoice(
            settings: TestoRunnerSettings,
            testTarget: PhpNamedElement,
            inheritor: PhpClass,
        ) {
            val selection = settings.toSelection()
            TestoRunContexts.applyInheritorChoice(selection, testTarget, inheritor)
            settings.load(selection)
        }

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
