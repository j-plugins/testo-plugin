package com.github.xepozz.testo.php

import com.github.xepozz.testo.tests.run.TestoReportTarget
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathMappingSettings
import com.intellij.openapi.util.io.NioFiles
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.config.commandLine.PhpCommandSettings
import com.jetbrains.php.config.commandLine.PhpCommandSettingsBuilder
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.run.PhpCommandLineSettings
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import com.jetbrains.php.util.pathmapper.PhpPathMapper
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

    // createPathMappings can miss what the interpreter's command line mounts (a Docker interpreter's project volume);
    // createPathMapper, the console's translation, has it.
    private val interpreterPaths: PhpCommandLinePathProcessor? by lazy {
        if (!isRemote) return@lazy null
        runCatching {
            PhpRemoteInterpreterManager.getInstance()?.createPathMapper(project, interpreter.phpSdkAdditionalData)
        }.getOrNull()
    }

    private val interpreterMapper: PhpPathMapper? by lazy { interpreterPaths?.let { runCatching { it.createPathMapper(project) }.getOrNull() } }

    /** A path as the interpreter sees it. An already-remote path comes back unchanged. */
    fun toInterpreterIfMapped(path: String): String = toMapped(path) ?: path

    /** The host view of an interpreter path, or null when no mapping covers it. */
    fun toLocal(path: String): String? {
        if (!isRemote) return path
        mappings?.convertToLocal(path)?.takeIf { it != path }?.let { return it }
        return runCatching { interpreterMapper?.getLocalPath(path) }.getOrNull()?.takeIf { it.isNotEmpty() && it != path }
    }

    /** A file the script writes and the IDE reads back afterwards. */
    fun output(localPath: String): TestoReportTarget = TestoReportTarget.resolve(project, interpreter, localPath)

    /**
     * The interpreter's view of a host directory the script only reads. One the interpreter cannot see (the IDE system
     * dir sits under no mapping) is copied where it can: the project's `.idea`, else [workingDirectory] — a container
     * may mount only part of the project, but never leaves out the directory the script runs in.
     */
    fun share(
        localDir: Path,
        stagingName: String,
        workingDirectory: String? = null,
        command: PhpCommandLinePathProcessor? = null,
    ): SharedDirectory {
        if (!isRemote) return SharedDirectory(localDir.toString(), null)
        mappings?.convertToRemote(localDir.toString())?.takeIf { it != localDir.toString() }
            ?.let { return SharedDirectory(it, null) }

        return stage(localDir, stagingName, stagingRoots(project.basePath, workingDirectory)) { toMapped(it, command) }
            ?: throw ExecutionException(
                "The interpreter '${interpreter.name}' cannot see $localDir, nor the project's .idea or the working directory"
            )
    }

    private fun toMapped(path: String, command: PhpCommandLinePathProcessor? = null): String? {
        mappings?.convertToRemote(path)?.takeIf { it != path }?.let { return it }
        return listOfNotNull(command, interpreterPaths).firstNotNullOfOrNull { processor ->
            runCatching { processor.takeIf { it.canProcess(path) }?.process(path) }.getOrNull()?.takeIf { it != path }
        }
    }

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
    ): PhpCommandSettings = command(script, workingDirectory, commandLineSettings, env, withDebugger) { scriptArguments }

    /** As above, with arguments that need the command's own path translation (what it mounts) to be spelled. */
    fun command(
        script: String,
        workingDirectory: String,
        commandLineSettings: PhpCommandLineSettings?,
        env: Map<String?, String?>,
        withDebugger: Boolean,
        scriptArguments: (PhpCommandLinePathProcessor?) -> List<String>,
    ): PhpCommandSettings {
        val command = PhpCommandSettingsBuilder(project, interpreter)
            .loadAndStartDebug(withDebugger)
            .build()
        val paths = if (command.isRemote) command.pathProcessor else null
        command.setWorkingDir(workingDirectory)
        command.setScript(toMapped(script, paths) ?: script, !command.isRemote)
        command.addArguments(scriptArguments(paths))
        commandLineSettings?.let { command.importCommandLineSettings(it, workingDirectory) }
        command.addEnvs(env)
        return command
    }

    /** [path] is what the interpreter reads; [staging] is the project-local copy to delete afterwards, if one was made. */
    class SharedDirectory(val path: String, val staging: Path?) {
        fun release() {
            val staging = staging ?: return
            NioFiles.deleteRecursively(staging)
            // The working directory's staging root is ours alone: nothing left in it but its .gitignore.
            val root = staging.parent
            runCatching {
                if (Files.list(root).use { entries -> entries.allMatch { it.fileName.toString() == ".gitignore" } }) {
                    NioFiles.deleteRecursively(root)
                }
            }
        }
    }

    companion object {
        private const val STAGING_DIR = "staging"

        /** Where a copy may go, in order: the project's `.idea`, then the working directory. */
        fun stagingRoots(basePath: String?, workingDirectory: String?): List<Path> = listOfNotNull(
            basePath?.let { Path.of(it, ".idea", "testo", STAGING_DIR) },
            workingDirectory?.let { Path.of(it, ".testo-$STAGING_DIR") },
        )

        /**
         * [localDir] copied under the first of [roots] that [toRemote] translates, or null when none is: the
         * translation is asked before anything is written.
         */
        fun stage(localDir: Path, stagingName: String, roots: List<Path>, toRemote: (String) -> String?): SharedDirectory? {
            for (root in roots) {
                val staging = root.resolve(FileUtil.sanitizeFileName(stagingName))
                val path = toRemote(staging.toString()) ?: continue
                NioFiles.deleteRecursively(staging)
                FileUtil.copyDir(localDir.toFile(), staging.toFile())
                Files.writeString(root.resolve(".gitignore"), "*\n")
                return SharedDirectory(path, staging)
            }
            return null
        }
    }
}
