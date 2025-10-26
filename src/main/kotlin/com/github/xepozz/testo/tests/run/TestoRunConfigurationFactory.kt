package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project

class TestoRunConfigurationFactory(private val configurationType: TestoRunConfigurationType) :
    ConfigurationFactory(configurationType) {
    override fun getId() = TestoRunConfigurationType.ID
    override fun getName() = configurationType.displayName
    override fun getIcon() = TestoIcons.TESTO

    override fun createTemplateConfiguration(project: Project) = TestoRunConfiguration(project, this)
}