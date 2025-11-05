package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.Consumer
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.phpunit.PhpMethodLocation
import com.jetbrains.php.phpunit.PhpUnitRuntimeConfigurationProducer
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
    override fun isEnabled(project: Project) = true

    override fun setupConfiguration(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement,
        virtualFile: VirtualFile
    ): PsiElement? {
        if (element is PhpClass) {
            val element = findTestElement(element, getWorkingDirectory(element)) as? PhpClass ?: return null

            return super.setupConfiguration(
                testRunnerSettings,
                element.containingFile,
                element.containingFile.virtualFile
            )
        }
        if (element is Function) {
            val element = findTestElement(element, getWorkingDirectory(element))
            if (element is Function) {
                val usages = TestoDataProviderUtils.findDataProviderUsages(element)

                if (usages.isNotEmpty()) {
//                    val target = usages.first()

                    return super.setupConfiguration(testRunnerSettings, element, element.containingFile.virtualFile)
                }
            }
        }
        return super.setupConfiguration(testRunnerSettings, element, virtualFile)
    }

    override fun isConfigurationFromContext(
        testRunnerSettings: PhpTestRunnerSettings,
        element: PsiElement
    ): Boolean {
        if (element is PhpClass) {
            return when {
                testRunnerSettings.scope != PhpTestRunnerSettings.Scope.File -> false
                testRunnerSettings.filePath != element.containingFile.virtualFile.path -> false
                else -> true
            }
        }
        if (element is Function) {
            val usages = TestoDataProviderUtils.findDataProviderUsages(element)

            if (usages.isNotEmpty()) {
                return false
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

    override fun onFirstRun(
        configuration: ConfigurationFromContext,
        context: ConfigurationContext,
        startRunnable: Runnable
    ) {
        val testoRunConfiguration = configuration.configuration as TestoRunConfiguration
        val testRunnerSettings = testoRunConfiguration.testoSettings.runnerSettings
        val location = context.getLocation()
        if (location is PsiLocation<*>) {
            val psiElement = location.psiElement
            val element = findTestElement(psiElement, getWorkingDirectory(location.psiElement))

            if (element is PhpClass) {
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

            if (element is Function && element.isTestoDataProviderLike()) {
                val dataSetUsages = TestoDataProviderUtils.findDataProviderUsages(element)
                println("dataSetUsages: $dataSetUsages for dataSet: $element")
                if (dataSetUsages.size > 1) {
                    showDataSetUsageChooser(
                        element,
                        dataSetUsages,
                        context,
                        testRunnerSettings,
                        startRunnable,
                        testoRunConfiguration,
                    )
                    return
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
            }
        }

        super.onFirstRun(configuration, context, startRunnable)
    }

    override fun findTestElement(element: PsiElement?, workingDirectory: VirtualFile?): PsiElement? {
        if (element == null || DumbService.getInstance(element.project).isDumb) return null

        val target = when (element) {
            is LeafPsiElement -> element.parent
            else -> element
        }
        return when (target) {
            is Function -> target.takeIf { it.isTestoExecutable() || it.isTestoDataProviderLike() }
            is PhpClass -> target.takeIf { it.isTestoClass() }
            is PhpFile -> target.takeIf { it.isTestoFile() }
            is PsiDirectory -> target.takeIf { PhpUnitRuntimeConfigurationProducer.checkDirectoryContainsPhpFiles(target.virtualFile, target.project) }
            else -> null
        }
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
            val testSubClasses = PhpIndex.getInstance(testClass.project).getAllSubclasses(testClass.fqn)
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
        configuration: TestoRunConfiguration
    ) {
        val callback = getRunDataSetUsagesCallback(
            testRunnerSettings,
            startRunnable,
            configuration,
            dataSetUsages,
            dataSet.name,
        )
        createChooserPopup(
            dataSetUsages,
            PhpBundle.message("choose.test.method.to.run.dataset.0", *arrayOf(dataSet.name)),
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
        dataSetName: String
    ): Consumer<Set<*>> {
        return Consumer { selectedValues: Set<*> ->
            val selected = selectedValues.firstOrNull() as? Method ?: return@Consumer

            testRunnerSettings.scope = PhpTestRunnerSettings.Scope.Method
            testRunnerSettings.methodName = selected.name
            testRunnerSettings.filePath = selected.containingFile.virtualFile.presentableUrl

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

    private fun getContainingClass(location: Location<*>, method: Method) = when (location) {
        is PhpMethodLocation -> location.containingClass
        else -> method.containingClass
    }

    companion object Companion {
        val METHOD = Condition<PsiElement> {
            it.isTestoExecutable() || (it is Method && TestoDataProviderUtils.isDataProvider(it))
        }
        private val METHOD_NAMER = { element: PsiElement? -> (element as? PhpNamedElement)?.name }
        private val FILE_TO_SCOPE = { file: PsiFile? ->
            file
                ?.takeIf { it.isTestoFile() }
                .apply { println("file to scope: $file -> $this") }
        }
    }
}
