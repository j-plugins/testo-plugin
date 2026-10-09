package com.github.xepozz.testo.launch

import com.intellij.openapi.project.Project

/** A report the IDE reads at [local] and the interpreter writes at [path]. */
interface TestoReportLocation {
    val local: String

    val path: String

    /** False when [path] is a host path the interpreter cannot reach. */
    val isReachable: Boolean

    /** Brings a report written on a remote host to [local]. Blocking; off the EDT. */
    fun copyToLocal(project: Project)

    companion object {
        /**
         * The local path of the report announced at [path], if one of [targets] is it or holds it: coverage-xml targets
         * a directory and is announced as the `index.xml` inside. Pure string work, whatever the host OS.
         */
        fun localPathOf(path: String, targets: List<TestoReportLocation>): String? = targets.firstNotNullOfOrNull { target ->
            val directory = target.path.trimEnd('/') + "/"
            when {
                path == target.path -> target.local
                path.startsWith(directory) -> target.local.trimEnd('/', '\\') + "/" + path.removePrefix(directory)
                else -> null
            }
        }
    }
}
