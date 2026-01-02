package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.isTestoFile
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.testFramework.run.PhpDefaultTestRunnerSettingsValidator

object TestoTestMethodFinder : PhpDefaultTestRunnerSettingsValidator.PhpTestMethodFinder {
    override fun find(file: PsiFile, testName: String): Boolean {
        if (!file.isTestoFile()) return false
        val setIndex = testName.indexOf(':')
        val functionName = when {
            setIndex == -1 -> testName
            else -> testName.substring(0, setIndex)
        }

        return PsiTreeUtil.findChildrenOfType(file, Function::class.java).any { it.name == functionName }
    }
}