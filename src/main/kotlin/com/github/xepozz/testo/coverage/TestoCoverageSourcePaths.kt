package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.php.TestoPhp
import com.intellij.openapi.project.Project

/**
 * [path] as it exists on this machine: as is, else through the first of [toLocal] that lands on an existing file.
 * Unresolved paths stay as they are.
 */
fun resolveCoverageSourcePath(path: String, toLocal: List<(String) -> String?>, exists: (String) -> Boolean): String {
    if (exists(path)) return path
    return toLocal.firstNotNullOfOrNull { map -> runCatching { map(path) }.getOrNull()?.takeIf { it != path && exists(it) } }
        ?: path
}

/** The interpreter → local translations a report's source paths may need — see [TestoPhp.coverageSourcePathMappers]. */
fun coverageSourcePathMappers(project: Project): List<(String) -> String?> =
    TestoPhp.getInstance().coverageSourcePathMappers(project)
