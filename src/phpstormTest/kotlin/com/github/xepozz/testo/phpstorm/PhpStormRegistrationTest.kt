package com.github.xepozz.testo.phpstorm

import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.testFramework.PhpTestFrameworkType

/**
 * The registrations that need the PHP plugin's classes come from included descriptors: testo-php.xml through
 * plugin.xml, testo-php-coverage.xml through coverage.xml. This checks the IDE actually loaded both, on whichever
 * platform the test runs.
 */
class PhpStormRegistrationTest : BasePlatformTestCase() {

    fun testMainDescriptorIsLoaded() {
        val type = ConfigurationTypeUtil.findConfigurationType("TestoRunConfiguration")
        assertNotNull("Testo run configuration type", type)
        assertTrue(
            "Testo run configuration producer",
            RunConfigurationProducer.getProducers(project).any { it.javaClass.simpleName == "TestoRunConfigurationProducer" },
        )
        assertNotNull("Testo debug runner", runner("TestoDebugRunner"))
        assertNotNull("Testo test framework type", PhpTestFrameworkType.getTestFrameworkType("Testo"))
        assertNotNull("New Test action", ActionManager.getInstance().getAction("TestoNewTestFromClass"))
        assertNotNull("Generate test method action", ActionManager.getInstance().getAction("TestoGenerateTestMethod"))
    }

    fun testCoverageDescriptorIsLoaded() {
        assertNotNull("Testo coverage runner", runner("TestoCoverageRunner"))
    }

    private fun runner(id: String): ProgramRunner<*>? = ProgramRunner.PROGRAM_RUNNER_EP.extensionList.firstOrNull { it.runnerId == id }
}
