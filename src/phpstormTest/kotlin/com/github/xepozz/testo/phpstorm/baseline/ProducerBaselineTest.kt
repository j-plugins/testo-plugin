package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.baseline.BaselineFixture
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfigurationProducer
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.testFramework.PhpTestFrameworkConfigurationIml
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager

/**
 * Which run each context produces, and which of the produced configurations each context recognises as its own.
 * Goes through the producer's public entry points, as the platform does for a gutter icon or a context menu.
 */
class ProducerBaselineTest : BasePlatformTestCase() {
    private val producer = TestoRunConfigurationProducer()
    private lateinit var projectFixture: BaselineFixture

    override fun setUp() {
        super.setUp()
        projectFixture = BaselineFixture(myFixture).also { it.setUp() }
        val configuration = PhpTestFrameworkConfigurationIml(TestoFrameworkType.INSTANCE)
        configuration.executablePath = "/opt/testo/bin/testo"
        PhpTestFrameworkSettingsManager.getInstance(project)
            .addSettingsIfAbsent(TestoFrameworkType.INSTANCE, configuration, null, null)
    }

    override fun tearDown() {
        try {
            BaselineSettings.clearFrameworkSettings(project)
            projectFixture.tearDown()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testProducedRunsAndRecognition() {
        val contexts = projectFixture.contexts()
        val produced = mutableListOf<Pair<String, TestoRunConfiguration>>()
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
            val configuration = fromContext.configuration as TestoRunConfiguration
            produced += label to configuration
            out.appendLine("  name=${configuration.name}")
            out.appendLine("  suggestedName=${configuration.suggestedName()} actionName=${configuration.actionName}")
            out.appendLine("  source=${BaselineSettings.describe(fromContext.sourceElement)}")
            out.appendLine(Baselines.dump(configuration.testoSettings.getTestoRunnerSettings()))
        }

        out.appendLine()
        out.appendLine("== recognition: row = produced configuration, column = context (X = recognised)")
        contexts.forEachIndexed { index, (label, _) -> out.appendLine("  c$index $label") }
        for ((label, configuration) in produced) {
            val row = contexts.joinToString("") { (_, element) ->
                val recognised = runCatching {
                    producer.isConfigurationFromContext(configuration, ConfigurationContext(element))
                }
                when {
                    recognised.isFailure -> "!"
                    recognised.getOrThrow() -> "X"
                    else -> "."
                }
            }
            out.appendLine("  $row  $label")
        }

        Baselines.assertMatches("producer", out.toString())
    }
}
