package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.util.SmartList
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.config.interpreters.PhpSdkFileTransfer
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager

class TestoRerunFailedTestsAction(
    componentContainer: ComponentContainer,
    properties: SMTRunnerConsoleProperties?
) : AbstractRerunFailedTestsAction(componentContainer) {
    init {
        this.init(properties)
    }

    override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
        val profile = this.myConsoleProperties.configuration
        if (profile !is TestoRunConfiguration) {
            LOG.warn(
                TestoBundle.message(
                    "php.testo.run.configuration.rerun.incorrect.configuration",
                    *arrayOf<Any>(profile.javaClass)
                )
            )
            return null
        }
            return object : MyRunProfile(profile) {
                @Throws(ExecutionException::class)
                override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
                    val runConfiguration = this.peer as TestoRunConfiguration
                    val project = runConfiguration.project
                    val interpreter: PhpInterpreter? = runConfiguration.interpreter
                    if (interpreter != null) {
                        val baseFile = runConfiguration.getBaseFile(null, interpreter)
                        val settingsManager = PhpTestFrameworkSettingsManager.getInstance(project)
                        val frameworkConfiguration = settingsManager.getOrCreateByInterpreter(
                            TestoFrameworkType.INSTANCE,
                            interpreter,
                            baseFile,
                            true
                        )
                        if (frameworkConfiguration != null) {
                            val clone = runConfiguration.clone() as TestoRunConfiguration
                            clone.settings.runnerSettings.filePath = ""
                            val command = clone.createCommand(
                                interpreter,
                                mutableMapOf(),
                                SmartList(mutableListOf("-g", "failed")),
                                frameworkConfiguration,
                                false,
                            )
                            PhpSdkFileTransfer
                                .getSdkFileTransfer(interpreter.phpSdkAdditionalData)
                                .updateCommand(command)
                            return runConfiguration.getState(environment, command, null)
                        }
                    }

                    return null
                }
            }
    }

    companion object {
        private val LOG = Logger.getInstance(TestoRerunFailedTestsAction::class.java)
    }
}
