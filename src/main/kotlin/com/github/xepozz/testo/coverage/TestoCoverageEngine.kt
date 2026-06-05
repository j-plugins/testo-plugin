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

    // The report path is left to the platform default (CoverageEnabledConfiguration.createCoverageFile()), an
    // IDE-managed path under <system>/coverage/<project>@<config>.xml — same convention as the PhpUnit/Codeception
    // engines. We pass that path to Testo via `--coverage-clover=<path>` (see TestoCoverageProgramRunner), so the tool
    // writes the Clover report exactly where the IDE reads it back, instead of a fixed runtime/ dir inside the project.
}

/**
 * The report path is IDE-managed (under [com.intellij.openapi.application.PathManager.getSystemPath]), so the platform's
 * default delete-on-disk confirmation never fires. We still skip deletion: the report is regenerated each run at the
 * same path, so there is nothing to clean up.
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
