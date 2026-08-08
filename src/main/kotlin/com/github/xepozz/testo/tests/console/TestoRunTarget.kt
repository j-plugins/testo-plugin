package com.github.xepozz.testo.tests.console

/**
 * How to run one node of the tree again, as its own service message spelled it out.
 *
 * The PSI a location hint resolves to is not enough to reproduce a run: a data set resolves to its class (the method
 * name carries coordinates no `findOwnMethodByName` will match), so a right-click on `Dataset #3 [3]` would rerun the
 * whole file. The hint itself, however, is exact — Testo builds its tail out of the very selector `--filter` takes:
 *
 * ```
 * php_qn://…/Calculator.php::\Testo\Bench\Internal\Calculator            a case
 * php_qn://…/Calculator.php::\Testo\Bench\Internal\Calculator::med       a test, or its DataProvider batch node
 * php_qn://…/Calculator.php::\Testo\Bench\Internal\Calculator::med:3:0   data provider #3, data set #0
 * php_qn://…/functions.php::\Testo\Bench\medianOf                        a free test function
 * ```
 *
 * [suite] and [type] come from the optional `testSuite` / `testType` attributes, which narrow the rerun the way the
 * node itself was narrowed. All three are optional: a Testo that sends none leaves an [isEmpty] target and the
 * producer is left to work off the PSI alone, exactly as before.
 */
data class TestoRunTarget(
    val locationHint: String? = null,
    val suite: String? = null,
    val type: String? = null,
) {
    /**
     * The tail of the hint — `\Ns\Class::med:3:0`, `\Ns\Class` or `\Ns\freeFunction` — which is exactly what
     * `--filter` takes, whichever of the three it is.
     *
     * A class selector matters as much as a method one: the element-based path narrows a case node to `--path <file>`,
     * and one file may declare several cases (`PipelineFailureSandbox.php` holding `TestLevelPipelineFailure` beside
     * its siblings), so without the filter a right-click on one case runs all of them.
     */
    val filter: String? get() = locationHint?.let(Companion::filterOf)

    /** Nothing here narrows anything: the node was announced without a hint and without either attribute. */
    val isEmpty: Boolean
        get() = filter == null && suite.isNullOrBlank() && type.isNullOrBlank()

    companion object {
        /**
         * `php_qn://<file>::<selector>` -> `<selector>`, or null when the hint points at a file and nothing more.
         *
         * The `#<index>` and ` with data set #N` suffixes are the plugin's own additions to a hint (line markers,
         * history lookup) rather than anything Testo sends, but a hint may reach this from either side, so both are
         * cut off — they are display coordinates, not part of a selector.
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
