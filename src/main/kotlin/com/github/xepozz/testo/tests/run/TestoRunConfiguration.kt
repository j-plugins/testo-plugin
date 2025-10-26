package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.TestoFrameworkType
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.TextFieldCompletionProvider
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.run.PhpAsyncRunConfiguration
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import com.jetbrains.php.testFramework.PhpTestFrameworkConfiguration
import com.jetbrains.php.testFramework.run.PhpTestRunConfiguration
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationEditor
import com.jetbrains.php.testFramework.run.PhpTestRunConfigurationSettings
import com.jetbrains.php.testFramework.run.PhpTestRunnerConfigurationEditor

class TestoRunConfiguration(project: Project, factory: ConfigurationFactory) : PhpTestRunConfiguration(
    project,
    factory,
    TestoBundle.message("testo.local.run.display.name"),
    TestoFrameworkType.INSTANCE,
    TestoTestRunnerSettingsValidator,
    TestoRunConfigurationHandler.INSTANCE,
), PhpAsyncRunConfiguration {
    val testoSettings
        get() = settings as TestoRunConfigurationSettings

    override fun createMethodFieldCompletionProvider(editor: PhpTestRunnerConfigurationEditor): TextFieldCompletionProvider {
        println("createMethodFieldCompletionProvider $editor")
        return object : TextFieldCompletionProvider() {
            override fun addCompletionVariants(text: String, offset: Int, prefix: String, result: CompletionResultSet) {
                println("addCompletionVariants: $text, $offset, $prefix")
            }
        }
    }

    override fun createSettings() = TestoRunConfigurationSettings()

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        val editor = super.getConfigurationEditor() as PhpTestRunConfigurationEditor
        editor.setRunnerOptionsDocumentation("https://infection.github.io/guide/command-line-options.html")

//        return this.addExtensionEditor(editor)!!
        return TestoTestRunConfigurationEditor(editor, this)
    }

    override fun getWorkingDirectory(
        project: Project,
        settings: PhpTestRunConfigurationSettings,
        config: PhpTestFrameworkConfiguration?
    ) = project.basePath


    override fun createTestConsoleProperties(executor: Executor): SMTRunnerConsoleProperties {
        println("createTestConsoleProperties")
        val manager = PhpRemoteInterpreterManager.getInstance()

        val pathProcessor = when (this.interpreter?.isRemote) {
            true -> manager?.createPathMapper(this.project, interpreter!!.phpSdkAdditionalData)
            else -> null
        } ?: PhpCommandLinePathProcessor.LOCAL

        val pathMapper = pathProcessor.createPathMapper(this.project)
        return TestoConsoleProperties(
            this,
            executor,
//            InfectionLocationProvider(pathMapper, this.project, this.getConfigurationFileRootPath())
        )
    }

    companion object Companion {
        const val ID = "InfectionConsoleCommandRunConfiguration"

//        val INSTANCE = TestoRunConfiguration()
    }
}