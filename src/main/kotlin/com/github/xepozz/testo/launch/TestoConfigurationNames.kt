package com.github.xepozz.testo.launch

import com.intellij.util.PathUtil

/** How a Testo run configuration names itself where the IDE's own name would say nothing or too much. */
object TestoConfigurationNames {

    /**
     * The name for [selection], or null to keep the IDE's own.
     *
     * A filter-only run has no file/method to name itself after (it is deliberately unscoped), and the IDE's name for an
     * unscoped configuration would be empty — it is named after whatever selects it instead. A configuration that DOES
     * point at a config file (ApplicationConfig/SuiteConfig runs) keeps the file-based name even if a group was typed
     * into it later.
     *
     * The IDE names a method run `<file>::<method field>`, which for a qualified selector repeats the class the file
     * name already implies: `Calculator.php::\Testo\Bench\Internal\Calculator::med:3:0`.
     */
    fun suggestedName(selection: TestoRunSelection): String? {
        if (isFilterOnlyRun(selection)) {
            val groups = selection.groups
            if (groups.isNotEmpty()) {
                val quoted = groups.joinToString(", ") { "'$it'" }
                return if (groups.size == 1) "Group $quoted" else "Groups $quoted"
            }
            val suites = selection.suites
            if (suites.isNotEmpty()) {
                val quoted = suites.joinToString(", ") { "'$it'" }
                return if (suites.size == 1) "Suite $quoted" else "Suites $quoted"
            }
            if (selection.testoType.isNotEmpty()) return "Type '${selection.testoType}'"
        }

        qualifiedMethodTail(selection)?.let {
            return "${PathUtil.getFileName(selection.filePath.orEmpty())}::$it"
        }

        return null
    }

    /**
     * The `med:3:0` of a `\Ns\Calculator::med:3:0` method field, or null when the field holds a plain method name.
     *
     * A run produced from a results-tree node puts the whole selector there — that is what Testo's `--filter` takes —
     * and everything that shows the configuration to the user is better off with just its tail.
     */
    fun qualifiedMethodTail(selection: TestoRunSelection): String? {
        if (selection.scope != TestoScope.METHOD) return null
        val method = selection.methodName ?: return null

        return method.substringAfterLast("::").takeIf { it != method && it.isNotEmpty() }
    }

    /**
     * Scope "configuration file" without an actual config file: the run is selected by Testo's own filters alone —
     * `--group`, `--suite`, `--type` or an explicit `--filter` list — and carries no path or method flag at all. Such a
     * run needs no configuration file: Testo falls back to ./testo.php in the working directory.
     */
    fun isFilterOnlyRun(selection: TestoRunSelection): Boolean {
        if (selection.scope != TestoScope.CONFIGURATION_FILE) return false
        if (selection.useAlternativeConfigurationFile) return false

        return selection.groups.isNotEmpty()
            || selection.excludeGroups.isNotEmpty()
            || selection.suites.isNotEmpty()
            || selection.testoType.isNotEmpty()
            || selection.rerunFilters.isNotEmpty()
    }
}
