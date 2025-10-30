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

        val phpClasses = PsiTreeUtil.findChildrenOfType(phpFile, PhpClass::class.java)

        return when {
            phpClasses.isEmpty() -> TestoIcons.Layered.FUNCTION
            phpClasses.size > 1 -> TestoIcons.Layered.FILE
            phpClasses.first().modifier.isAbstract -> TestoIcons.Layered.Class.CLASS_ABSTRACT
            phpClasses.first().modifier.isFinal -> TestoIcons.Layered.Class.CLASS_FINAL
            else -> TestoIcons.Layered.Class.CLASS
        }
    }
}
