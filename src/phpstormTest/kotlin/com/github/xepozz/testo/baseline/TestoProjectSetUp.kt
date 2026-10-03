package com.github.xepozz.testo.baseline

import com.github.xepozz.testo.phpstorm.baseline.BaselineSettings
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.jetbrains.php.testFramework.PhpTestFrameworkConfigurationIml
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager

/**
 * Sets Testo up in a test project the way a PhpStorm user has it: an entry on the Test Frameworks page naming the
 * executable. Each PHP implementation's tests provide this object under the same name, so the core tests set Testo up
 * without knowing which implementation they run on.
 */
object TestoProjectSetUp {
    fun setUp(fixture: CodeInsightTestFixture) {
        val configuration = PhpTestFrameworkConfigurationIml(TestoFrameworkType.INSTANCE)
        configuration.executablePath = "/opt/testo/bin/testo"
        PhpTestFrameworkSettingsManager.getInstance(fixture.project)
            .addSettingsIfAbsent(TestoFrameworkType.INSTANCE, configuration, null, null)
    }

    fun tearDown(fixture: CodeInsightTestFixture) = BaselineSettings.clearFrameworkSettings(fixture.project)
}
