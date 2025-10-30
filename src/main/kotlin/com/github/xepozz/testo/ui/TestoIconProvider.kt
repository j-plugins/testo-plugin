package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.isTestoFile
import com.intellij.ide.IconProvider
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpClass
import javax.swing.Icon

class TestoIconProvider : IconProvider() {
    override fun getIcon(element: PsiElement, @Iconable.IconFlags flags: Int): Icon? {
        val phpFile = element as? PhpFile ?: return null

        if (!phpFile.isTestoFile()) return null

        val phpClass = PsiTreeUtil.findChildOfType(phpFile, PhpClass::class.java)

        return when {
            phpClass == null -> TestoIcons.TEST_FILE
            phpClass.modifier.isAbstract -> TestoIcons.ABSTRACT_TESTO_CLASS
            phpClass.modifier.isFinal -> TestoIcons.FINAL_TESTO_CLASS
            else -> TestoIcons.TEST_FILE
        }
    }
}
