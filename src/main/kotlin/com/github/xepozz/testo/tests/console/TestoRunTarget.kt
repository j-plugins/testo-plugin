package com.github.xepozz.testo.tests.console

/**
 * How to run one tree node again, as its own service message spelled it out.
 *
 * The PSI a hint resolves to is not enough: a data set has no method of its own to find, so it lands on its class and
 * the run widens to the file. The hint is exact — Testo builds its tail out of the very selector `--filter` takes:
 *
 * ```
 * php_qn://…/Calculator.php::\Ns\Calculator            a case
 * php_qn://…/Calculator.php::\Ns\Calculator::med       a test, or its DataProvider batch node
 * php_qn://…/Calculator.php::\Ns\Calculator::med:3:0   data provider #3, data set #0
 * php_qn://…/functions.php::\Ns\medianOf               a free test function
 * ```
 *
 * [suite] and [type] come from the optional `testSuite` / `testType` attributes. All three are optional; without them
 * the target is [isEmpty] and the producer works off the PSI alone.
 */
data class TestoRunTarget(
    val locationHint: String? = null,
    val suite: String? = null,
    val type: String? = null,
) {
    /**
     * The tail of the hint, which is what `--filter` takes.
     *
     * A class selector matters as much as a method one: the element-based path narrows a case to `--path <file>`, and
     * one file may declare several cases.
     */
    val filter: String? get() = locationHint?.let(Companion::filterOf)

    val isEmpty: Boolean
        get() = filter == null && suite.isNullOrBlank() && type.isNullOrBlank()

    companion object {
        /**
         * `php_qn://<file>::<selector>` → `<selector>`, or null for a hint naming nothing but a file.
         *
         * `#<index>` and ` with data set #N` are the plugin's own display coordinates (line markers, history
         * lookup), never part of a selector, so a hint arriving from either side is cut back.
         */
        fun filterOf(hint: String): String? = hint
            .substringBefore(" with data set")
            .substringBefore('#')
            .substringAfter("://")
            .substringAfter("::", "")
            .trim()
            .ifEmpty { null }
    }
}
