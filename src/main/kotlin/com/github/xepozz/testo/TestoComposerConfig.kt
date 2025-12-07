package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.php.testFramework.PhpTestFrameworkComposerConfig
import com.jetbrains.php.testFramework.PhpTestFrameworkConfigurationIml
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import kotlin.io.path.Path

class TestoComposerConfig : PhpTestFrameworkComposerConfig(TestoFrameworkType.INSTANCE, PACKAGE, RELATIVE_PATH) {
    override fun getDefaultConfigName() = "testo.php"

    override fun getConfigurationType() = TestoRunConfigurationType.INSTANCE

    override fun findConfigurationFile(project: Project, composerConfig: VirtualFile?): VirtualFile? {
        return super.findConfigurationFile(project, composerConfig)
    }
    override fun updateConfigurations(project: Project, configFile: VirtualFile?, composerConfig: VirtualFile?) {
        val testFrameworkSettingsManager = PhpTestFrameworkSettingsManager.getInstance(project)
        if (testFrameworkSettingsManager.getConfigurations(TestoFrameworkType.INSTANCE).isNotEmpty()) {
            return
        }

        val runnableBinary = findFromComposerVendor(project, composerConfig)
            ?.let { Path(it) }
            ?.let { VfsUtil.findFile(it, false) }

        val configuration = PhpTestFrameworkConfigurationIml(TestoFrameworkType.INSTANCE)
        configuration.executablePath = runnableBinary?.path

        testFrameworkSettingsManager.addSettingsIfAbsent(
            TestoFrameworkType.INSTANCE,
            configuration,
            null,
            runnableBinary,
        )
    }

    companion object Companion {
        private const val PACKAGE = "testo/testo"
        private const val RELATIVE_PATH = "bin/testo"
    }
}
