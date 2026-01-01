package com.github.xepozz.testo.tests.run

import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.testFramework.run.PhpDefaultTestRunnerSettingsValidator

object TestoTestRunnerSettingsValidator : PhpDefaultTestRunnerSettingsValidator(
    listOf(PhpFileType.INSTANCE),
    TestoTestMethodFinder,
    false,
    false,
)

