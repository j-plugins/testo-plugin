package com.github.xepozz.testo.tests.run

import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.testFramework.run.PhpDefaultTestRunnerSettingsValidator

object TestoTestRunnerSettingsValidator : PhpDefaultTestRunnerSettingsValidator(
    listOf(PhpFileType.INSTANCE),
    AnyMethodIsValid,
    false,
    false,
)

/**
 * Accepts whatever the Method field holds, which switches off the "Cannot find 'X' in 'Y.php'" gate —
 * `PhpDefaultTestRunnerSettingsValidator.isMethodValid` raises that error, and only that error, when the finder
 * answers no. Its other two checks are untouched: the file must still be a PHP file, and the field must not be empty.
 *
 * The gate assumes the field names a member the file declares. For Testo it does not: it holds a `--filter` selector,
 * and every shape Testo accepts is one PHP declares nothing under —
 *
 * ```
 * med:1:0                                      attribute #1 of `med`, its data set #0
 * \Testo\Bench\Internal\Calculator::med:1:0    the same, qualified — how a tree node reruns itself
 * \Tests\Sandbox\Self\AssertTest               a case
 * \Testo\Bench\medianOf                        a free test function
 * ```
 *
 * — so the gate has to be taught each shape as it appears, and it was: first the qualified method, then the case. Each
 * omission stopped a run that Testo would have executed correctly, behind a dialog whose "Run anyway" then proved it.
 * Testo is the authority on whether a filter matches anything, and it reports an empty selection itself, through
 * `buildProblem` — which this plugin already surfaces.
 */
private object AnyMethodIsValid : PhpDefaultTestRunnerSettingsValidator.PhpTestMethodFinder {
    override fun find(file: PsiFile, testName: String) = true
}
