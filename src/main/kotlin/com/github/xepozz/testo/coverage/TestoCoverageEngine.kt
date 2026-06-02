package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.jetbrains.php.phpunit.coverage.PhpCoverageSuite
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageRunner


class TestoCoverageEnabledConfiguration(
    configuration: TestoRunConfiguration
) : CoverageEnabledConfiguration(configuration, CoverageRunner.getInstance(PhpUnitCoverageRunner::class.java)) {
    override fun coverageFileNameSeparator(): String = "@"

    // Testo writes the Clover report to a path hardcoded inside the project's `testo.php`
    // (see `new CloverReport(__DIR__ . '/runtime/clover.xml')`). Until Testo exposes a
    // `--coverage-clover=<path>` CLI flag, we mirror that convention so the IDE reads
    // coverage from the same place Testo writes it.
    override fun getCoverageFilePath(): String =
        configuration.project.basePath?.let { "$it/runtime/clover.xml" } ?: super.getCoverageFilePath()!!
}

class TestoCoverageEngine : PhpUnitCoverageEngine() {
    override fun isApplicableTo(conf: RunConfigurationBase<*>) = conf is TestoRunConfiguration

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>) =
        TestoCoverageEnabledConfiguration(conf as TestoRunConfiguration)

    override fun createCoverageSuite(
        covRunner: CoverageRunner,
        name: String,
        coverageDataFileProvider: CoverageFileProvider,
        config: CoverageEnabledConfiguration
    ): CoverageSuite? {
        if (config is TestoCoverageEnabledConfiguration) {
            return PhpCoverageSuite(
                name,
                config.configuration.project,
                covRunner,
                coverageDataFileProvider,
                config.createTimestamp()
            )
        }

        return null
    }
}
