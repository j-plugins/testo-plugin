package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoFrameworkType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager

object TestoUtil {
    fun isEnabled(project: Project): Boolean =
        PhpTestFrameworkSettingsManager
            .getInstance(project)
            .getConfigurations(TestoFrameworkType.INSTANCE)
            .firstOrNull()
            ?.let { configurations ->
                !configurations.isLocal || StringUtil.isNotEmpty(configurations.executablePath)
            }
            ?: false
}