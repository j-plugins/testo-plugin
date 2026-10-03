package com.github.xepozz.testo.baseline

import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.PhpClassReferenceView
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.PhpNewExpressionView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Which run each context produces and which produced run each context recognises as its own, in terms of the core's
 * model of a run — the same answers from any PHP implementation. The producer is the one the platform finds for the
 * Testo configuration type, as for a gutter icon or a context menu.
 */
class RunContextsBaselineTest : BasePlatformTestCase() {
    private lateinit var projectFixture: BaselineFixture

    private val php get() = TestoPhp.getInstance()

    @Suppress("UNCHECKED_CAST")
    private val producer: RunConfigurationProducer<RunConfiguration>
        get() = RunConfigurationProducer.getProducers(project)
            .single { it.configurationType.id == TESTO_CONFIGURATION_TYPE } as RunConfigurationProducer<RunConfiguration>

    override fun setUp() {
        super.setUp()
        projectFixture = BaselineFixture(myFixture).also { it.setUp() }
        TestoProjectSetUp.setUp(myFixture)
    }

    override fun tearDown() {
        try {
            TestoProjectSetUp.tearDown(myFixture)
            projectFixture.tearDown()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testRunsProducedFromContextsAndTheirRecognition() {
        val contexts = projectFixture.contexts()
        val produced = mutableListOf<Pair<String, RunConfiguration>>()
        val out = StringBuilder()

        for ((label, element) in contexts) {
            out.appendLine("== $label")
            val result = runCatching { producer.createConfigurationFromContext(ConfigurationContext(element)) }
            result.exceptionOrNull()?.let {
                out.appendLine("  error: ${it.javaClass.simpleName}: ${it.message}")
                continue
            }
            val fromContext = result.getOrNull()
            if (fromContext == null) {
                out.appendLine("  <no run>")
                continue
            }
            val configuration = fromContext.configuration
            produced += label to configuration
            out.appendLine("  name=${configuration.name}")
            (configuration as? LocatableConfigurationBase<*>)?.let {
                out.appendLine("  suggestedName=${it.suggestedName()} actionName=${it.actionName}")
            }
            out.appendLine("  source=${describe(fromContext.sourceElement)}")
            out.appendLine(dump((configuration as TestoConfiguration).selection))
        }

        out.appendLine()
        out.appendLine("== recognition: row = produced configuration, column = context (X = recognised)")
        contexts.forEachIndexed { index, (label, _) -> out.appendLine("  c$index $label") }
        for ((label, configuration) in produced) {
            val row = contexts.joinToString("") { (_, element) ->
                val recognised = runCatching { producer.isConfigurationFromContext(configuration, ConfigurationContext(element)) }
                when {
                    recognised.isFailure -> "!"
                    recognised.getOrThrow() -> "X"
                    else -> "."
                }
            }
            out.appendLine("  $row  $label")
        }

        BaselineFiles.assertMatches("run-contexts", out.toString())
    }

    /** What a run stands for, by the PHP construct it is — not by the PSI class, which is the implementation's own. */
    private fun describe(element: PsiElement?): String {
        if (element == null) return "<none>"
        if (element is PsiDirectory) return "directory(${element.name})"
        if (element is PsiFile) return "file(${element.name})"
        val kind = when (php.view(element)) {
            is PhpClassView -> "class"
            is PhpFunctionView -> "function"
            is PhpAttributeView -> "attribute"
            is PhpClassReferenceView -> "reference"
            is PhpNewExpressionView -> "new"
            else -> if (php.isYield(element)) "yield" else "element"
        }
        return "$kind(${element.text.lineSequence().first().trim().take(60)})"
    }

    private fun dump(selection: TestoRunSelection): String = with(selection) {
        buildString {
            appendLine("  scope=$scope")
            appendLine("  selectedType=$selectedType")
            appendLine("  directoryPath=$directoryPath")
            appendLine("  filePath=$filePath")
            appendLine("  methodName=$methodName")
            appendLine("  useAlternativeConfigurationFile=$useAlternativeConfigurationFile")
            appendLine("  configurationFilePath=$configurationFilePath")
            appendLine("  testRunnerOptions=$testRunnerOptions")
            appendLine("  command=$command")
            appendLine("  testoType=$testoType")
            appendLine("  suites=$suites")
            appendLine("  groups=$groups")
            appendLine("  excludeGroups=$excludeGroups")
            appendLine("  rerunFilters=$rerunFilters")
            appendLine("  dataProviderIndex=$dataProviderIndex")
            appendLine("  dataSetIndex=$dataSetIndex")
            appendLine("  coverageDriver=$coverageDriver")
            appendLine("  coverage=[clover=$coverageClover, cobertura=$coverageCobertura, xml=$coverageXml]")
            appendLine("  coverageLevel=$coverageLevel")
            appendLine("  coverageOptions=$coverageOptions")
            appendLine("  parallel=$parallel enabled=$parallelTestingEnabled")
            append("  logs=[html=$logHtml, junit=$logJunit]")
        }
    }

    private companion object {
        /** The id every Testo configuration carries, whichever implementation registers its type. */
        const val TESTO_CONFIGURATION_TYPE = "TestoRunConfiguration"
    }
}
