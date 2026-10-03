package com.github.xepozz.testo.phpstorm.coverage

import com.github.xepozz.testo.infection.TestoMutationExecutor
import com.github.xepozz.testo.infection.mutateAfterArchive
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment

class TestoMutationProgramRunner : TestoCoverageProgramRunner() {
    override fun getRunnerId(): String = "TestoMutationRunner"
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == TestoMutationExecutor.ID && profile is TestoRunConfiguration

    override fun prepare(configuration: TestoRunConfiguration): TestoRunConfiguration =
        (configuration.clone() as TestoRunConfiguration).apply {
            testoSettings.runnerSettings.coverageXml = true
            testoSettings.runnerSettings.logJunit = true
        }

    override fun started(env: ExecutionEnvironment, properties: TestoConsoleProperties) = mutateAfterArchive(env, properties)
}
