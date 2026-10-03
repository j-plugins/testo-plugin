package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.TestoTestLocator
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import ru.openide.openphp.run.testing.PhpTestPathTranslator
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment

/**
 * Testo's console properties on OpenIDE: reported paths come back through the [environment] the run went out through,
 * and tree nodes are located by PHP for OpenIDE's locator.
 */
fun openIdeConsoleProperties(
    configuration: RunConfiguration,
    executor: Executor,
    environment: PhpLaunchEnvironment,
) = TestoConsoleProperties(
    configuration,
    executor,
    OpenIdePathMapping(environment),
    TestoTestLocator(PhpTestPathTranslator.of(environment)),
)
