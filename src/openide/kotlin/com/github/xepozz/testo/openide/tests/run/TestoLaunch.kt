package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.launch.TestoCommandLine
import com.github.xepozz.testo.launch.TestoReportLocation
import com.github.xepozz.testo.launch.TestoRunSelection
import com.github.xepozz.testo.launch.TestoScope
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.run.TestoReportFlags
import com.github.xepozz.testo.tests.run.TestoRunPaths
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.ParametersList
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import ru.openide.openphp.run.testing.PhpTestFrameworks
import ru.openide.openphp.settings.launch.PhpExposedPath
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * One run of a Testo configuration in one launch environment: the binary, the working directory, the configuration file
 * and the command, decided once. Read under a read action and off the EDT: finding the binary reads the project's
 * composer files.
 */
internal class TestoLaunch(
    private val project: Project,
    private val selection: TestoRunSelection,
    private val options: TestoRunConfigurationOptions,
    private val configurationName: String,
    val environment: PhpLaunchEnvironment,
) {
    /** The binary: the configuration's, else the test framework page's, else composer's; null when there is none. */
    val binary: String? = options.binaryPath?.takeIf { it.isNotBlank() }
        ?: TestoFrameworkType.instance()?.let { PhpTestFrameworks.binaryPath(project, it, options.workingDirectory) }

    /** The configuration file passed with `--config`: the one the configuration names, else the framework's. */
    val configFile: String? =
        if (selection.useAlternativeConfigurationFile) selection.configurationFilePath?.takeIf { it.isNotBlank() }
        else TestoFrameworkType.instance()?.let {
            PhpTestFrameworks.configFile(project, it, options.workingDirectory?.takeIf(String::isNotBlank) ?: binaryProjectRoot())
        }

    /**
     * Where the process runs, by Testo's rules: the configuration's own directory, else that of a `testo.php` it runs,
     * else the project the binary belongs to, else the project root.
     */
    val workingDirectory: String = TestoRunPaths.resolveWorkingDirectory(
        customWorkingDirectory = options.workingDirectory,
        configurationFilePath = localConfigurationFile(),
        executableRoot = { binaryProjectRoot() },
        fallback = { project.basePath },
    ) ?: throw ExecutionException("Cannot run Testo: the project has no directory to run in.")

    private fun localConfigurationFile(): String? {
        val path = configFile ?: return null
        val host = FileUtil.toSystemIndependentName(environment.toHost(path))
        val original = FileUtil.toSystemIndependentName(path)
        // An unmapped container path does not identify a host directory for relative --path arguments.
        return host.takeIf {
            environment.locality == PhpLaunchLocality.SameHost || host != original ||
                environment.toEnvironment(host) != host || File(host).isFile
        }
    }

    private fun binaryProjectRoot(): String? {
        val binary = binary ?: return null
        val basePath = project.basePath ?: return null
        return TestoRunPaths.projectRootOfExecutable(environment.toHost(binary), basePath) { File(it).exists() }
    }

    /** Whether [binary] is there to run, as far as the IDE can tell where it runs. */
    fun checkBinary(): BinaryCheck = checkBinary(binary, environment)

    /**
     * The `--log-html` / `--log-junit` reports of this run, made visible to the environment. A report the environment
     * cannot see refuses the run rather than getting lost.
     */
    fun reportTargets(): List<Report> {
        if (!selection.logHtml && !selection.logJunit) return emptyList()
        return listOfNotNull(
            TestoReportFlags.htmlReportFile(project, configurationName).takeIf { selection.logHtml },
            TestoReportFlags.junitReportFile(project, configurationName).takeIf { selection.logJunit },
        ).map { local -> report(local) }
    }

    fun report(local: Path): Report {
        // Testo's writers create the directory themselves, but a missing one is the one avoidable failure between here
        // and a written report — and a container can only mount a directory that exists.
        runCatching { Files.createDirectories(local.parent) }
        return when (val exposed = environment.exposeFile(local.toString())) {
            is PhpExposedPath.Visible -> Report(local.toString(), exposed.pathInEnvironment, exposed)
            is PhpExposedPath.Mounted -> Report(local.toString(), exposed.pathInEnvironment, exposed)
            is PhpExposedPath.Unreachable -> throw ExecutionException(
                "Cannot run Testo: the report $local is out of reach of ${environment.displayName}: ${exposed.reason}",
            )
        }
    }

    /**
     * The command: `php [phpArguments] <binary> <command> <runner options> [testoArguments] <selection> <reports>
     * [--config <file>] <scope>`, the order a PhpStorm run has.
     */
    fun commandLine(
        reports: List<Report>,
        phpArguments: List<String> = emptyList(),
        testoArguments: List<String> = emptyList(),
        environmentVariables: Map<String, String> = emptyMap(),
        exposures: List<PhpExposedPath> = emptyList(),
    ): GeneralCommandLine {
        val binary = binary ?: throw ExecutionException(BinaryCheck.NotFound.message)
        val arguments = buildList {
            add(selection.command.ifBlank { TestoRunConfigurationOptions.DEFAULT_COMMAND })
            addAll(ParametersList.parse(selection.testRunnerOptions.orEmpty()))
            addAll(testoArguments)
            addAll(TestoCommandLine.selectionArguments(selection))
            reports.forEach { add(it.flag) }
            configFile?.let {
                add(TestoCommandLine.CONFIG_OPTION)
                add(environment.toEnvironment(it))
            }
            addAll(scopeArguments())
        }
        val command = environment.command(
            phpArgs = phpArguments + environment.toEnvironment(binary) + arguments,
            workDir = File(workingDirectory),
            env = options.environmentVariables + environmentVariables,
            exposures = exposures + reports.map { it.exposed },
        )
        if (!options.passParentEnvironment) {
            command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        }
        return command
    }

    // `--path` is relative to the working directory, and so computed from host paths before anything is translated.
    private fun scopeArguments(): List<String> = when (selection.scope) {
        TestoScope.TYPE -> TestoCommandLine.typeArguments(selection.selectedType.orEmpty())
        TestoScope.DIRECTORY -> TestoCommandLine.directoryArguments(selection.directoryPath.orEmpty(), workingDirectory)
        TestoScope.FILE -> TestoCommandLine.fileArguments(selection.filePath.orEmpty(), workingDirectory)
        TestoScope.METHOD ->
            TestoCommandLine.methodArguments(selection.filePath.orEmpty(), selection.methodName.orEmpty(), workingDirectory)
        TestoScope.CONFIGURATION_FILE -> emptyList()
    }

    /** A report the IDE reads at [local] and the process writes at [path], which the environment already sees. */
    class Report(override val local: String, override val path: String, val exposed: PhpExposedPath) : TestoReportLocation {
        val flag: String
            get() = if (local.endsWith(".html")) "--log-html=$path" else "--log-junit=$path"

        override val isReachable: Boolean get() = true

        // Visible or mounted: the process writes straight to the local file.
        override fun copyToLocal(project: Project) {}
    }

    companion object {
        /**
         * Whether [binary] is there, checked only where the IDE can tell. A path in the host's form is checked on the
         * host. In WSL a path of the distribution is checked through its translation to the host, and one that
         * translates to nothing cannot be checked. A container may keep the binary in a volume of its own, so a binary
         * missing on the host proves nothing there.
         */
        fun checkBinary(binary: String?, environment: PhpLaunchEnvironment): BinaryCheck {
            binary ?: return BinaryCheck.NotFound
            return when (environment.locality) {
                PhpLaunchLocality.SameHost -> if (File(binary).isFile) BinaryCheck.Found else BinaryCheck.Missing(binary)
                is PhpLaunchLocality.WslDistribution -> {
                    // A distribution path starts with a slash; a Windows host path never does.
                    val onHost = if (binary.startsWith("/")) environment.toHost(binary).takeIf { it != binary } else binary
                    when {
                        onHost == null -> BinaryCheck.Unverified(binary)
                        File(onHost).isFile -> BinaryCheck.Found
                        else -> BinaryCheck.Missing(binary)
                    }
                }
                is PhpLaunchLocality.Container ->
                    if (File(binary).isFile || File(environment.toHost(binary)).isFile) BinaryCheck.Found
                    else BinaryCheck.NotOnHost(binary)
            }
        }
    }
}

/** What the IDE can tell about the Testo binary before a run. */
sealed interface BinaryCheck {
    /** Shown to the user; null when there is nothing to say. */
    val message: String?

    /** Whether the run must not start. */
    val refuses: Boolean get() = false

    object Found : BinaryCheck {
        override val message: String? get() = null
    }

    object NotFound : BinaryCheck {
        override val message: String
            get() = "Testo executable is not found. Add testo/testo to composer.json or set the path in " +
                "Settings | PHP | Test Frameworks."
        override val refuses: Boolean get() = true
    }

    data class Missing(val path: String) : BinaryCheck {
        override val message: String
            get() = "Testo executable $path does not exist. Run composer install or set the path in " +
                "Settings | PHP | Test Frameworks."
        override val refuses: Boolean get() = true
    }

    /** A distribution path the IDE has no host path for; the run may still find it. */
    data class Unverified(val path: String) : BinaryCheck {
        override val message: String
            get() = "Testo executable $path is not checked: the IDE cannot see that path of the WSL distribution."
    }

    /** Missing on this machine, which proves nothing for a container. */
    data class NotOnHost(val path: String) : BinaryCheck {
        override val message: String
            get() = "Testo executable $path is not on this machine; the run expects to find it in the container."
    }
}
