package com.github.xepozz.testo.tests.run

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.testFramework.run.PhpDefaultTestRunnerSettingsValidator

object TestoTestRunnerSettingsValidator: PhpDefaultTestRunnerSettingsValidator(
    listOf(PhpFileType.INSTANCE),
    PhpTestMethodFinder { file: PsiFile, testName: String ->
        PsiTreeUtil.findChildrenOfType(file, Function::class.java).any { it.name == testName }
    },
    false,
    false,
)