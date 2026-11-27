package com.github.xepozz.testo.openide.coverage

import com.github.xepozz.testo.infection.TestoMutationExecutor
import com.github.xepozz.testo.infection.mutateAfterArchive
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.openide.tests.run.testoConfigurationOf
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.ExecutionEnvironment

class TestoMutationProgramRunner : TestoCoverageProgramRunner() {
    override fun getRunnerId(): String = "TestoMutationRunner"
    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == TestoMutationExecutor.ID && testoConfigurationOf(profile) != null

    override fun configurationForRun(configuration: TestoRunConfiguration): TestoRunConfiguration =
        (configuration.clone() as TestoRunConfiguration).apply {
            selection = selection.copy(coverageXml = true, logJunit = true)
        }

    override fun started(env: ExecutionEnvironment, properties: TestoConsoleProperties) = mutateAfterArchive(env, properties)
}
