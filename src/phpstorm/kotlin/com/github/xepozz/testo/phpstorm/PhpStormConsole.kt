package com.github.xepozz.testo.phpstorm

import com.github.xepozz.testo.phpstorm.tests.TestoTestLocator
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunConfiguration
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.util.pathmapper.PhpPathMapper

/**
 * Testo's console properties on PhpStorm: reported paths go back through [pathMapper] (and out through [processor]),
 * tree nodes are located by the PhpStorm-based locator.
 */
fun phpStormConsoleProperties(
    configuration: RunConfiguration,
    executor: Executor,
    pathMapper: PhpPathMapper,
    processor: PhpCommandLinePathProcessor = PhpCommandLinePathProcessor.LOCAL,
) = TestoConsoleProperties(
    configuration,
    executor,
    PhpStormPathMapping(processor) { pathMapper },
    TestoTestLocator(pathMapper),
)
