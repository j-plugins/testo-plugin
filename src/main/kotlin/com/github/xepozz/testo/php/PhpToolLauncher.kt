package com.github.xepozz.testo.php

import com.github.xepozz.testo.tests.run.TestoReportTarget
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathMappingSettings
import com.intellij.openapi.util.io.NioFiles
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.commandLine.PhpCommandSettingsBuilder
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.run.PhpCommandLineSettings
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs a PHP script from a project's toolchain (`vendor/bin/testo`, `vendor/bin/infection`, …) on an interpreter that
 * may be local, WSL, Docker or SSH, and moves paths across that boundary in both directions.
 */
internal class PhpToolLauncher(private val project: Project, val interpreter: PhpInterpreter) {
    val isRemote: Boolean get() = interpreter.isRemote

    val mappings: PathMappingSettings? by lazy {
        if (!isRemote) return@lazy null
        runCatching {
            PhpRemoteInterpreterManager.getInstance()?.createPathMappings(project, interpreter.phpSdkAdditionalData)
        }.getOrNull()
    }

    /** A path as the interpreter sees it. convertToRemote hands back an already-remote path unchanged. */
    fun toInterpreterIfMapped(path: String): String = mappings?.convertToRemote(path) ?: path

    /** The host view of an interpreter path, or null when no mapping covers it. */
    fun toLocal(path: String): String? {
        if (!isRemote) return path
        return mappings?.convertToLocal(path)?.takeIf { it != path }
    }

    /** A file the script writes and the IDE reads back afterwards. */
    fun output(localPath: String): TestoReportTarget = TestoReportTarget.resolve(project, interpreter, localPath)

    /**
     * The interpreter's view of a host directory the script only reads. One the interpreter cannot see (the IDE system
     * dir sits under no mapping) is copied into the project's `.idea`, which every mapped interpreter mounts.
     */
    fun share(localDir: Path, stagingName: String): SharedDirectory {
        if (!isRemote) return SharedDirectory(localDir.toString(), null)
        toMappedPath(localDir)?.let { return SharedDirectory(it, null) }

        val base = project.basePath ?: throw unreachable(localDir)
        val staging = Path.of(base, ".idea", "testo", STAGING_DIR, FileUtil.sanitizeFileName(stagingName))
        NioFiles.deleteRecursively(staging)
        FileUtil.copyDir(localDir.toFile(), staging.toFile())
        Files.writeString(staging.parent.resolve(".gitignore"), "*\n")
        val path = toMappedPath(staging) ?: run {
            NioFiles.deleteRecursively(staging)
            throw unreachable(localDir)
        }
        return SharedDirectory(path, staging)
    }

    private fun toMappedPath(local: Path): String? =
        mappings?.convertToRemote(local.toString())?.takeIf { it != local.toString() }

    private fun unreachable(localDir: Path) = ExecutionException(
        "The interpreter '${interpreter.name}' cannot see $localDir, and the project directory is not mapped for it either"
    )

    /**
     * A command running [script] (host or interpreter path) in [workingDirectory] (a host path), followed by
     * [scriptArguments], with the configuration's own interpreter options and env applied.
     */
    fun command(
        script: String,
        workingDirectory: String,
        commandLineSettings: PhpCommandLineSettings?,
        env: Map<String?, String?>,
        withDebugger: Boolean,
        scriptArguments: List<String> = emptyList(),
    ): PhpCommandSettings {
        val command = PhpCommandSettingsBuilder(project, interpreter)
            .loadAndStartDebug(withDebugger)
            .build()
        command.setWorkingDir(workingDirectory)
        command.setScript(toInterpreterIfMapped(script), !command.isRemote)
        command.addArguments(scriptArguments)
        commandLineSettings?.let { command.importCommandLineSettings(it, workingDirectory) }
        command.addEnvs(env)
        return command
    }

    /** [path] is what the interpreter reads; [staging] is the project-local copy to delete afterwards, if one was made. */
    class SharedDirectory(val path: String, val staging: Path?) {
        fun release() {
            staging?.let(NioFiles::deleteRecursively)
        }
    }

    companion object {
        private const val STAGING_DIR = "staging"
    }
}
