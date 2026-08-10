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
 * Switches off the "Cannot find 'X' in 'Y.php'" gate, which `isMethodValid` raises — and only that one; the
 * file-type and non-empty checks stay.
 *
 * The gate assumes the Method field names a member the file declares. It holds a `--filter` selector instead, and
 * every shape Testo accepts (`med:1:0`, `\Ns\Case::med:1:0`, `\Ns\Case`, `\Ns\freeFunction`) is one PHP declares
 * nothing under, so the gate stopped runs Testo executes correctly. Whether a filter matches anything is Testo's
 * call, and it reports an empty selection itself through `buildProblem`.
 */
private object AnyMethodIsValid : PhpDefaultTestRunnerSettingsValidator.PhpTestMethodFinder {
    override fun find(file: PsiFile, testName: String) = true
}
