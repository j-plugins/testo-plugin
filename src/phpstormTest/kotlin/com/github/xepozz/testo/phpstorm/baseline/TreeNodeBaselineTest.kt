package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.baseline.BaselineFixture
import com.github.xepozz.testo.phpstorm.phpStormConsoleProperties
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfigurationProducer
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoNodeIndex
import com.github.xepozz.testo.tests.console.TestoRunTarget
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestFrameworkRunningModel
import com.intellij.execution.testframework.TestTreeView
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.testFramework.PhpTestFrameworkConfigurationIml
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import java.lang.reflect.Proxy

/**
 * Running a node of the results tree: what the node announced (suite, type, selector) is laid over the run the PSI
 * gives, and a configuration counts as the node's own only on an exact match.
 */
class TreeNodeBaselineTest : BasePlatformTestCase() {
    private val producer = TestoRunConfigurationProducer()
    private lateinit var projectFixture: BaselineFixture
    private lateinit var properties: TestoConsoleProperties

    override fun setUp() {
        super.setUp()
        projectFixture = BaselineFixture(myFixture).also { it.setUp() }
        val configuration = PhpTestFrameworkConfigurationIml(TestoFrameworkType.INSTANCE)
        configuration.executablePath = "/opt/testo/bin/testo"
        PhpTestFrameworkSettingsManager.getInstance(project)
            .addSettingsIfAbsent(TestoFrameworkType.INSTANCE, configuration, null, null)
        properties = phpStormConsoleProperties(
            BaselineSettings.newConfiguration(project, "Tree"),
            DefaultRunExecutor.getRunExecutorInstance(),
            PhpPathMapper.create(emptyList()),
        )
        Disposer.register(testRootDisposable, properties)
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

    private fun bind(proxy: SMTestProxy, nodeId: String) {
        val bind = TestoNodeIndex::class.java.getDeclaredMethod("bind", SMTestProxy::class.java, String::class.java)
        bind.isAccessible = true
        bind.invoke(properties.nodeIndex, proxy, nodeId)
    }

    private fun nodeContext(nodeId: String, target: TestoRunTarget, element: PsiElement): ConfigurationContext {
        val proxy = SMTestProxy(nodeId, false, target.locationHint)
        bind(proxy, nodeId)
        properties.targetStore.note(nodeId, target)
        val model = Proxy.newProxyInstance(
            TestFrameworkRunningModel::class.java.classLoader,
            arrayOf(TestFrameworkRunningModel::class.java),
        ) { _, method, _ ->
            when {
                method.name == "getProperties" -> properties
                method.returnType == java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as TestFrameworkRunningModel
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(AbstractTestProxy.DATA_KEY, proxy)
            .add(TestTreeView.MODEL_DATA_KEY, model)
            .add(Location.DATA_KEY, PsiLocation.fromPsiElement(element))
            .add(CommonDataKeys.PSI_ELEMENT, element)
            .build()
        return ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
    }

    fun testTreeNodes() {
        val f = projectFixture
        val calcPath = f.files.getValue("tests/CalcTest.php").virtualFile.path
        val functionsPath = f.files.getValue("tests/functions.php").virtualFile.path
        val testoPath = f.files.getValue("testo.php").virtualFile.path
        val median = f.leaf("tests/CalcTest.php", "function median", shift = 9).parent
        val calcClass = f.leaf("tests/CalcTest.php", "class CalcTest", shift = 6).parent
        val medianOf = f.leaf("tests/functions.php", "function medianOf", shift = 9).parent

        val nodes = listOf(
            Triple("data set node", TestoRunTarget("php_qn://$calcPath::\\App\\CalcTest::median:0:1", "Unit", "test"), median),
            Triple("method node", TestoRunTarget("php_qn://$calcPath::\\App\\CalcTest::median", null, "test"), median),
            Triple("class node, bench type", TestoRunTarget("php_qn://$calcPath::\\App\\CalcTest", null, "bench"), calcClass),
            Triple("function data set node", TestoRunTarget("php_qn://$functionsPath::\\App\\medianOf:0:0", null, "test"), medianOf),
            Triple("config file node, suite only", TestoRunTarget("php_qn://$testoPath", "Unit", null), f.files.getValue("testo.php")),
        )

        val out = StringBuilder()
        val produced = mutableListOf<Pair<String, TestoRunConfiguration>>()
        val contexts = mutableListOf<Pair<String, ConfigurationContext>>()
        nodes.forEachIndexed { index, (label, target, element) ->
            val context = nodeContext("node-$index", target, element)
            contexts += "$label (node)" to context
            contexts += "$label (element)" to ConfigurationContext(element)
            out.appendLine("== $label")
            val fromContext = runCatching { producer.createConfigurationFromContext(context) }
            fromContext.exceptionOrNull()?.let { out.appendLine("  error: ${it.javaClass.simpleName}: ${it.message}") }
            val configuration = fromContext.getOrNull()?.configuration as? TestoRunConfiguration
            if (configuration == null) {
                out.appendLine("  <no run>")
                return@forEachIndexed
            }
            produced += label to configuration
            out.appendLine("  name=${configuration.name} actionName=${configuration.actionName}")
            out.appendLine(Baselines.dump(configuration.testoSettings.getTestoRunnerSettings()).replace(calcPath, "<CalcTest>"))
        }

        out.appendLine()
        out.appendLine("== recognition: row = configuration made from a node, column = context")
        contexts.forEachIndexed { index, (label, _) -> out.appendLine("  c$index $label") }
        for ((label, configuration) in produced) {
            val row = contexts.joinToString("") { (_, context) ->
                val recognised = runCatching { producer.isConfigurationFromContext(configuration, context) }
                when {
                    recognised.isFailure -> "!"
                    recognised.getOrThrow() -> "X"
                    else -> "."
                }
            }
            out.appendLine("  $row  $label")
        }
        Baselines.assertMatches("tree-nodes", out.toString())
    }
}
