package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.isTestoFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.testFramework.run.PhpDefaultTestRunnerSettingsValidator

object TestoTestMethodFinder : PhpDefaultTestRunnerSettingsValidator.PhpTestMethodFinder {
    override fun find(file: PsiFile, testName: String): Boolean {
        if (!file.isTestoFile()) return false
        val functionName = declaredName(testName) ?: return false

        return PsiTreeUtil.findChildrenOfType(file, Function::class.java).any { it.name == functionName }
    }

    /**
     * The name the file actually declares, out of whatever the Method field holds.
     *
     * That field is a selector, not a name: it may carry the data-provider and data-set coordinates
     * (`med:1:0`) and, for a run produced from a results-tree node, the whole class in front of them
     * (`\Testo\Bench\Internal\Calculator::med:1:0`) — that is the form Testo's `--filter` takes and the only one
     * that picks a single data set. Both are stripped; what is left is a plain function name to look up.
     */
    fun declaredName(testName: String): String? = testName
        .substringAfterLast("::")
        .substringBefore(':')
        .trim()
        .ifEmpty { null }
}