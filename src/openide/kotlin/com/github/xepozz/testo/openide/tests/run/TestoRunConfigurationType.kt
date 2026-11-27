package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue

class TestoRunConfigurationType : SimpleConfigurationType(
    ID,
    TestoBundle.message("testo.local.run.display.name"),
    null,
    NotNullLazyValue.createValue { TestoIcons.TESTO },
) {
    override fun createTemplateConfiguration(project: Project) = TestoRunConfiguration(project, this)

    override fun getOptionsClass(): Class<out RunConfigurationOptions> = TestoRunConfigurationOptions::class.java

    companion object {
        /** The id saved configurations carry; the same as PhpStorm's, so a project's configurations mean the same. */
        const val ID = "TestoRunConfiguration"

        fun instance(): TestoRunConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(TestoRunConfigurationType::class.java)
    }
}
