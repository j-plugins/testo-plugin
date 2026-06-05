package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue

class TestoRunConfigurationType :
    SimpleConfigurationType(
        ID,
        TestoBundle.message("testo.local.run.display.name"),
        null,
        NotNullLazyValue.createValue { TestoIcons.TESTO },
    ) {
    override fun createTemplateConfiguration(project: Project) =
        TestoRunConfiguration(project, this)

    companion object Companion {
        // Pinned literal (was ::class.simpleName): a future class rename must not silently change the
        // persisted run-configuration type id and break users' saved configs.
        const val ID = "TestoRunConfiguration"

        val INSTANCE
            get() = findConfigurationType(TestoRunConfigurationType::class.java)
    }
}