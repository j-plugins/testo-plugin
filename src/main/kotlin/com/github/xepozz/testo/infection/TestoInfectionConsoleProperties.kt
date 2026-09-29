package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.ui.TestoReportViewer
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The console of a mutation run: Infection's own TeamCity stream, one node per mutant under one per source file. */
internal class TestoInfectionConsoleProperties(
    config: TestoRunConfiguration,
    executor: Executor,
    pathMapper: PhpPathMapper,
    private val launch: TestoInfectionLaunch,
) : SMTRunnerConsoleProperties(config, FRAMEWORK_NAME, executor), SMCustomMessagesParsing {
    private val locator = TestoInfectionLocator(pathMapper)

    @Volatile
    private var root: SMTestProxy.SMRootTestProxy? = null

    override fun setConsole(console: ConsoleView?) {
        super.setConsole(console)
        root = (console as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode
    }

    override fun isIdBasedTestTree(): Boolean = true

    override fun getTestLocator(): SMTestLocator = locator

    override fun createTestEventsConverter(
        testFrameworkName: String,
        consoleProperties: TestConsoleProperties,
    ): OutputToGeneralTestEventsConverter = TestoInfectionEventsConverter(testFrameworkName, consoleProperties, ::finishRun)

    private fun finishRun() {
        ApplicationManager.getApplication().executeOnPooledThread {
            launch.htmlTarget?.copyToLocal(project)
            launch.shared?.release()
            val report = launch.htmlReport.takeIf(Files::isRegularFile) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                root?.addLast { printer ->
                    printer.printHyperlink(TestoBundle.message("infection.report.open"), HyperlinkInfo { openReport(it, report) })
                    printer.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
                }
                NotificationGroupManager.getInstance().getNotificationGroup("Testo")
                    .createNotification(TestoBundle.message("infection.finished"), NotificationType.INFORMATION)
                    .addAction(object : DumbAwareAction(TestoBundle.message("infection.report.open")) {
                        override fun actionPerformed(e: AnActionEvent) = openReport(project, report)
                    })
                    .notify(project)
            }
        }
    }

    companion object {
        const val FRAMEWORK_NAME = "Infection"

        fun openReport(project: Project, report: Path) {
            if (!TestoReportViewer.open(project, report, TestoBundle.message("infection.report.label"))) {
                BrowserUtil.browse(report.toUri())
            }
        }
    }
}

/** Infection sends `testStdOut` without a `nodeId`; the id-based tree cannot place it, so it is matched by name. */
internal class TestoInfectionEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    private val onFinished: () -> Unit,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
    private val nodeIds = ConcurrentHashMap<String, String>()
    private val finished = AtomicBoolean()

    override fun processServiceMessage(message: ServiceMessage, visitor: ServiceMessageVisitor) {
        val attributes = message.attributes
        val name = attributes["name"].orEmpty()
        when (message.messageName) {
            "testStarted" -> attributes["nodeId"]?.let { nodeIds[name] = it }
            "testStdOut", "testStdErr" -> if (attributes["nodeId"].isNullOrEmpty()) {
                val stdOut = message.messageName == "testStdOut"
                val text = attributes[if (stdOut) "out" else "err"].orEmpty()
                val nodeId = nodeIds[name]
                if (nodeId != null) processor?.onTestOutput(TestOutputEvent(name, nodeId, "$text\n", stdOut))
                else processor?.onUncapturedOutput("$text\n", ProcessOutputTypes.STDOUT)
                return
            }
        }
        super.processServiceMessage(message, visitor)
    }

    override fun finishTesting() {
        super.finishTesting()
        if (finished.compareAndSet(false, true)) onFinished()
    }
}

/** `infection://<file>::<start>-<end>` (character offsets of the mutated code) and `file://<file>`. */
internal class TestoInfectionLocator(private val pathMapper: PhpPathMapper) : SMTestLocator {
    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        val hint = parseInfectionLocation(protocol, path) ?: return emptyList()
        val file = pathMapper.getLocalFile(hint.file) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val element = hint.offset?.let(psiFile::findElementAt) ?: psiFile
        return listOf(PsiLocation(element))
    }
}

internal data class InfectionLocation(val file: String, val offset: Int?)

internal fun parseInfectionLocation(protocol: String, path: String): InfectionLocation? = when (protocol) {
    "file" -> path.takeIf { it.isNotEmpty() }?.let { InfectionLocation(it, null) }
    "infection" -> {
        val separator = path.lastIndexOf("::")
        if (separator <= 0) null
        else InfectionLocation(path.substring(0, separator), path.substring(separator + 2).substringBefore('-').toIntOrNull())
    }
    else -> null
}
