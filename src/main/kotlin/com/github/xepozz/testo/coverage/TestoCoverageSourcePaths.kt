package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.php.PhpToolLauncher
import com.github.xepozz.testo.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.tests.run.TestoRunConfigurationType
import com.intellij.execution.RunManager
import com.intellij.openapi.project.Project
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.interpreters.PhpInterpreter

/**
 * [path] as it exists on this machine: as is, else through the first of [toLocal] that lands on an existing file.
 * Unresolved paths stay as they are.
 */
fun resolveCoverageSourcePath(path: String, toLocal: List<(String) -> String?>, exists: (String) -> Boolean): String {
    if (exists(path)) return path
    return toLocal.firstNotNullOfOrNull { map -> runCatching { map(path) }.getOrNull()?.takeIf { it != path && exists(it) } }
        ?: path
}

/**
 * The remote → local translations a report's source paths may need: a report written in a container or WSL names
 * files by the interpreter's paths. Taken from the project rather than from the run, since a suite is also loaded from
 * a manual import or after a restart, with no run around it.
 */
fun coverageSourcePathMappers(project: Project): List<(String) -> String?> {
    val configured = RunManager.getInstance(project)
        .getConfigurationsList(TestoRunConfigurationType.INSTANCE)
        .mapNotNull { (it as? TestoRunConfiguration)?.interpreter }
    val default = runCatching { PhpProjectConfigurationFacade.getInstance(project).interpreter }.getOrNull()
    return (configured + listOfNotNull(default))
        .filter(PhpInterpreter::isRemote)
        .distinctBy { it.name }
        .map { interpreter -> PhpToolLauncher(project, interpreter)::toLocal }
}
