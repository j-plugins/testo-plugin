package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.jetbrains.php.testFramework.PhpTestFrameworkComposerConfig

class TestoComposerConfig : PhpTestFrameworkComposerConfig(TestoFrameworkType.INSTANCE, PACKAGE, RELATIVE_PATH) {
    override fun getDefaultConfigName() = "testo.php"

    override fun getConfigurationType() = TestoRunConfigurationType.INSTANCE

    companion object Companion {
        private const val PACKAGE = "testo/testo"
        private const val RELATIVE_PATH = "bin/testo"
    }
}
