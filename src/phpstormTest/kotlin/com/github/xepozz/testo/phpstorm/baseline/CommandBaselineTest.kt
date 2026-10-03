package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.phpstorm.coverage.TestoCoverageProgramRunner
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.run.TestoReportTarget
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunnerSettings
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.phpunit.coverage.PhpUnitCoverageEngine.CoverageEngine
import com.jetbrains.php.testFramework.PhpTestFrameworkConfigurationIml
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings.Scope
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The whole command line a Testo run and a Testo Coverage run start, as the process sees it. Built by the
 * configuration's own `createCommand` and started through its own process handler, with a stand-in `php` that exits at
 * once — so the line is exactly what the platform would launch, down to argument order.
 */
class CommandBaselineTest : BasePlatformTestCase() {
    private lateinit var phpDir: Path
    private lateinit var record: Path
    private lateinit var interpreter: PhpInterpreter
    private lateinit var wd: String

    override fun setUp() {
        super.setUp()
        phpDir = Files.createTempDirectory("testo-baseline-php")
        record = phpDir.resolve("argv.txt")
        // Records where it ran and every argument on its own line, so the baseline shows the exact argv.
        val php = phpDir.resolve("php")
        Files.writeString(php, "#!/bin/sh\n{ echo \"cwd=${'$'}(pwd -P)\"; for a in \"${'$'}@\"; do echo \"arg=${'$'}a\"; done; } > '${record}'\nexit 0\n")
        php.toFile().setExecutable(true)
        interpreter = PhpInterpreter().apply {
            name = "Baseline PHP"
            homePath = php.toString()
        }
        wd = Files.createDirectories(phpDir.resolve("work/app")).toRealPath().toString()
        // The Coverage runner looks the framework up in the project settings rather than taking it as an argument.
        PhpTestFrameworkSettingsManager.getInstance(project)
            .addSettingsIfAbsent(TestoFrameworkType.INSTANCE, framework(), null, null)
    }

    override fun tearDown() {
        try {
            BaselineSettings.clearFrameworkSettings(project)
            phpDir.toFile().deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun framework(defaultConfig: String? = null) =
        PhpTestFrameworkConfigurationIml(TestoFrameworkType.INSTANCE).apply {
            executablePath = "$wd/vendor/bin/testo"
            if (defaultConfig != null) {
                configurationFilePath = defaultConfig
                isUseConfigurationFile = true
            }
        }

    private fun configuration(customWorkingDirectory: String? = "", setup: TestoRunnerSettings.() -> Unit) =
        BaselineSettings.newConfiguration(project, "Baseline").also { configuration ->
            configuration.settings.commandLineSettings.workingDirectory = customWorkingDirectory?.ifEmpty { wd }
            configuration.testoSettings.getTestoRunnerSettings().setup()
        }

    private fun commandLineOf(command: PhpCommandSettings, configuration: TestoRunConfiguration): String {
        Files.deleteIfExists(record)
        val handler: ProcessHandler = configuration.createProcessHandler(project, command, false)
        val process = (handler as OSProcessHandler).process
        try {
            check(process.waitFor(20, TimeUnit.SECONDS)) { "The stand-in php did not exit" }
            val argv = if (Files.exists(record)) Files.readString(record).trimEnd() else "<not started>"
            return normalise("commandLine=${handler.commandLine}\n$argv")
        } finally {
            handler.destroyProcess()
        }
    }

    private fun normalise(text: String) = text
        .replace(wd, "<wd>")
        .replace(phpDir.toRealPath().toString(), "<php-dir>")
        .replace(phpDir.toString(), "<php-dir>")
        .replace(PathManager.getSystemPath(), "<system>")
        .replace(project.basePath.orEmpty(), "<project>")
        // The IDE-managed report folder is keyed by the project's location hash, which a light test project generates.
        .replace(project.locationHash, "<project-hash>")

    private fun StringBuilder.case(label: String, block: () -> String) {
        appendLine("== $label")
        val result = runCatching(block)
        appendLine(
            result.fold(
                { "  ${it.replace("\n", "\n  ")}" },
                { "  error: ${it.javaClass.simpleName}: ${normalise(it.message.orEmpty())}" },
            )
        )
    }

    private fun run(configuration: TestoRunConfiguration, framework: PhpTestFrameworkConfigurationIml = framework()): String {
        val command = configuration.createCommand(interpreter, mutableMapOf(), mutableListOf(), framework, false)
        return commandLineOf(command, configuration)
    }

    fun testRunCommands() {
        val out = StringBuilder()
        out.case("defaults: file scope") { run(configuration { scope = Scope.File; filePath = "$wd/tests/CalcTest.php" }) }
        out.case("directory scope") { run(configuration { scope = Scope.Directory; directoryPath = "$wd/tests" }) }
        out.case("directory scope = working directory") { run(configuration { scope = Scope.Directory; directoryPath = wd }) }
        out.case("method: data set selector") {
            run(configuration { scope = Scope.Method; filePath = "$wd/tests/CalcTest.php"; methodName = "med:3:0" })
        }
        out.case("method: selector with # data provider") {
            run(configuration { scope = Scope.Method; filePath = "$wd/tests/CalcTest.php"; methodName = "testSum#provider" })
        }
        out.case("method: qualified selector") {
            run(configuration { scope = Scope.Method; filePath = "$wd/tests/CalcTest.php"; methodName = "\\App\\CalcTest::med:3:0" })
        }
        out.case("groups and excluded groups") {
            run(configuration {
                scope = Scope.ConfigurationFile
                groups = mutableListOf("db", "a b")
                excludeGroups = mutableListOf("slow", "!flaky")
            })
        }
        out.case("suites and type") {
            run(configuration {
                scope = Scope.ConfigurationFile
                suites = mutableListOf("Unit", "My Suite")
                testoType = "bench"
            })
        }
        out.case("rerun filters") {
            run(configuration {
                scope = Scope.ConfigurationFile
                rerunFilters = listOf("\\App\\CalcTest::adds", "\\App\\medianOf")
            })
        }
        out.case("explicit configuration file") {
            run(configuration {
                scope = Scope.ConfigurationFile
                isUseAlternativeConfigurationFile = true
                configurationFilePath = "$wd/config/testo.php"
            })
        }
        out.case("explicit testo.php, no custom working directory") {
            val nested = Files.createDirectories(Path.of(wd, "nested")).toString()
            run(configuration(customWorkingDirectory = null) {
                scope = Scope.ConfigurationFile
                isUseAlternativeConfigurationFile = true
                configurationFilePath = "$nested/testo.php"
            })
        }
        out.case("configuration file from framework settings") {
            run(configuration { scope = Scope.File; filePath = "$wd/tests/CalcTest.php" }, framework("$wd/testo.php"))
        }
        out.case("type scope") { run(configuration { scope = Scope.Type; selectedType = "Unit" }) }
        out.case("runner options, command, reports") {
            run(configuration {
                scope = Scope.File
                filePath = "$wd/tests/CalcTest.php"
                testRunnerOptions = "--stop-on-failure --no-ansi"
                command = "list"
                logHtml = true
                logJunit = true
            })
        }
        out.case("reports off") {
            run(configuration { scope = Scope.File; filePath = "$wd/tests/CalcTest.php"; logHtml = false; logJunit = false })
        }
        out.case("file outside the working directory") {
            run(configuration { scope = Scope.File; filePath = "/elsewhere/tests/CalcTest.php" })
        }
        out.case("no executable") {
            run(configuration { scope = Scope.File; filePath = "$wd/tests/CalcTest.php" }, framework().apply { executablePath = "" })
        }
        Baselines.assertMatches("command-run", out.toString())
    }

    private fun coverage(configuration: TestoRunConfiguration, localCoverage: String? = COVERAGE): String {
        val runner = TestoCoverageProgramRunner()
        val settings = configuration.testoSettings.getTestoRunnerSettings()
        // The assembly doExecute performs before building the command.
        val flags = runner.coverageFlagLocalPaths(settings, localCoverage)
        val targets = flags.map { (_, local) -> TestoReportTarget.resolve(project, interpreter, local) }
        val reportArguments =
            if (flags.isEmpty()) listOf("--coverage")
            else flags.zip(targets) { (format, _), target -> runner.coverageFlagFor(format, target.path) }
        val arguments = reportArguments + runner.extraCoverageArguments(settings)
        val header = "testoArguments=${arguments.joinToString(" ")}"
        if (settings.coverageEngine != CoverageEngine.PCOV) return header
        val command = runner.createTestoCoverageCommand(configuration, interpreter, arguments, localCoverage, localCoverage)
        return header + "\n" + commandLineOf(command, configuration)
    }

    fun testCoverageCommands() {
        val out = StringBuilder()
        val file: TestoRunnerSettings.() -> Unit = { scope = Scope.File; filePath = "$wd/tests/CalcTest.php" }
        out.case("xdebug, default formats, auto level") { coverage(configuration(setup = file)) }
        out.case("xdebug, all formats") { coverage(configuration { file(); coverageClover = true }) }
        out.case("xdebug, only coverage-xml, auto level") {
            coverage(configuration { file(); coverageCobertura = false; coverageXml = true })
        }
        out.case("xdebug, formats off") { coverage(configuration { file(); coverageCobertura = false; coverageXml = false }) }
        out.case("xdebug, no base path") { coverage(configuration(setup = file), localCoverage = null) }
        out.case("xdebug, level line, own options") {
            coverage(configuration { file(); coverageLevel = "line"; coverageOptions = "--type=test --no-ansi" })
        }
        out.case("pcov, default formats, auto level") {
            coverage(configuration { file(); coverageEngine = CoverageEngine.PCOV })
        }
        out.case("pcov, level branch, group run") {
            coverage(configuration {
                scope = Scope.ConfigurationFile
                groups = mutableListOf("db")
                coverageEngine = CoverageEngine.PCOV
                coverageLevel = "branch"
            })
        }
        out.case("pcov, options empty, method run") {
            coverage(configuration {
                scope = Scope.Method
                filePath = "$wd/tests/CalcTest.php"
                methodName = "med:3:0"
                coverageEngine = CoverageEngine.PCOV
                coverageOptions = ""
            })
        }
        Baselines.assertMatches("command-coverage", out.toString())
    }

    private companion object {
        const val COVERAGE = "/work/coverage/Baseline@testo.xml"
    }
}
