package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.intellij.coverage.CoverageFileProvider
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.CoverageSuite
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.project.Project
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

/**
 * The Clover report lives inside the project (runtime/clover.xml), not under [com.intellij.openapi.application.PathManager.getSystemPath].
 * The platform's default [CoverageSuite.deleteCachedCoverageData] stays silent only for files under the system dir;
 * for a project file it pops a "Delete file '…' on disk?" confirmation on every rerun. The report is regenerated each
 * run, so we don't delete it — which also removes the prompt.
 */
class TestoCoverageSuite(
    name: String,
    project: Project,
    coverageRunner: CoverageRunner,
    fileProvider: CoverageFileProvider,
    timeStamp: Long,
) : PhpCoverageSuite(name, project, coverageRunner, fileProvider, timeStamp) {
    override fun deleteCachedCoverageData() = Unit
}

class TestoCoverageEngine : PhpUnitCoverageEngine() {
    override fun isApplicableTo(conf: RunConfigurationBase<*>) = conf is TestoRunConfiguration

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>) =
        TestoCoverageEnabledConfiguration(conf as TestoRunConfiguration)

    override fun createCoverageSuite(
        name: String,
        project: Project,
        coverageRunner: CoverageRunner,
        fileProvider: CoverageFileProvider,
        timeStamp: Long,
        config: CoverageEnabledConfiguration
    ): CoverageSuite? {
        if (config is TestoCoverageEnabledConfiguration) {
            return TestoCoverageSuite(name, project, coverageRunner, fileProvider, timeStamp)
        }

        return super.createCoverageSuite(name, project, coverageRunner, fileProvider, timeStamp, config)
    }
}
