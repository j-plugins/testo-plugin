package com.github.xepozz.testo.openide

import com.intellij.execution.configurations.GeneralCommandLine
import ru.openide.openphp.settings.launch.PhpExposedPath
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.io.File

/**
 * A launch environment with rules a test states outright: [mappings] from a host directory to the one the process sees,
 * [exposed] for what a report path turns into, [modules] for what the module probe finds. Its command shows plainly
 * what the plugin passed: a local one runs `php`, any other runs `env <locality>` with the mounts, the working
 * directory and then `php`.
 */
class FakeLaunchEnvironment(
    override val locality: PhpLaunchLocality = PhpLaunchLocality.SameHost,
    private val mappings: Map<String, String> = emptyMap(),
    private val exposed: (String) -> PhpExposedPath = { PhpExposedPath.Visible(it) },
    private val modules: Set<String> = emptySet(),
    override val projectRoot: String = "",
    override val displayName: String = "fake",
) : PhpLaunchEnvironment {
    override fun command(
        phpArgs: List<String>,
        workDir: File?,
        env: Map<String, String>,
        exposures: List<PhpExposedPath>,
    ): GeneralCommandLine {
        val prefix = when (locality) {
            PhpLaunchLocality.SameHost -> emptyList()
            else -> listOf("env", locality.toString()) +
                exposures.filterIsInstance<PhpExposedPath.Mounted>().flatMap { it.dockerFlags } +
                listOfNotNull(workDir?.let { "-w=" + toEnvironment(it.path) })
        }
        val command = prefix + "php" + phpArgs
        return GeneralCommandLine(command).withEnvironment(env).apply {
            if (locality == PhpLaunchLocality.SameHost) workDir?.let { withWorkDirectory(it) }
        }
    }

    override fun toEnvironment(hostPath: String): String =
        mappings.entries.firstOrNull { hostPath == it.key || hostPath.startsWith(it.key + "/") }
            ?.let { it.value + hostPath.removePrefix(it.key) } ?: hostPath

    override fun toHost(pathInEnvironment: String): String =
        mappings.entries.firstOrNull { pathInEnvironment == it.value || pathInEnvironment.startsWith(it.value + "/") }
            ?.let { it.key + pathInEnvironment.removePrefix(it.value) } ?: pathInEnvironment

    override fun exposeFile(hostFile: String, mountAt: String?): PhpExposedPath = exposed(hostFile)

    override fun modules(workDir: File?): Set<String> = modules
}
