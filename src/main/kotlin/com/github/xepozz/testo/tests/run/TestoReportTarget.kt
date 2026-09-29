package com.github.xepozz.testo.tests.run

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.remote.RemoteSdkAdditionalData
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.phpunit.coverage.PhpCoverageResultManager
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager

/**
 * A report the IDE reads at [local] and the interpreter writes at [path]. The PHP plugin's coverage result manager is
 * the one public host → interpreter translation for a file outside the project mappings, so every report goes through
 * it, coverage or not: WSL gets the `/mnt/…` view of the same file, SSH a file under the remote helpers directory that
 * [copyToLocal] downloads.
 */
internal class TestoReportTarget private constructor(
    val local: String,
    val path: String,
    private val manager: PhpCoverageResultManager?,
    /** False when a remote interpreter has neither a manager nor a mapping for [local]: [path] is a host path there. */
    val isReachable: Boolean,
) {
    // copyFromRemote is protected; the public route, attachToProcess, also loads the report through the PHPUnit
    // coverage runner, which would race our own merged apply.
    fun copyToLocal(project: Project) {
        val manager = manager ?: return
        val copy = generateSequence<Class<*>>(manager.javaClass) { it.superclass }
            .firstNotNullOfOrNull { type ->
                type.declaredMethods.firstOrNull {
                    it.name == "copyFromRemote" && it.parameterTypes.contentEquals(arrayOf(Project::class.java))
                }
            } ?: return
        runCatching {
            copy.isAccessible = true
            copy.invoke(manager, project)
        }.onFailure { thisLogger().warn("Could not copy the report $path back from the interpreter", it) }
    }

    companion object {
        // One manager per report: the SSH one remembers a single local/remote pair.
        fun resolve(project: Project, interpreter: PhpInterpreter, local: String): TestoReportTarget {
            val data = interpreter.phpSdkAdditionalData
            if (data !is RemoteSdkAdditionalData) return TestoReportTarget(local, local, null, true)
            val remoteManager = PhpRemoteInterpreterManager.getInstance()
                ?: return TestoReportTarget(local, local, null, false)
            // Throws when no coverage manager accepts the interpreter type; its path mappings are all that is left then.
            runCatching { remoteManager.getCoverageResultManager(data) }.getOrNull()?.let { manager ->
                return TestoReportTarget(local, manager.processCoverageFile(local), manager, true)
            }
            val mapped = runCatching { remoteManager.createPathMappings(project, data).convertToRemote(local) }.getOrNull()
                ?: local
            return TestoReportTarget(local, mapped, null, mapped != local)
        }
    }
}
