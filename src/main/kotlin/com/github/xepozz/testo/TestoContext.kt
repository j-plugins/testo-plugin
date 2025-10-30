package com.github.xepozz.testo

import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.liveTemplates.PhpTemplateContextType

class TestoContext : PhpTemplateContextType("Testo") {
    override fun isInContext(element: PsiElement): Boolean {
        val parent = element.parent
        return when {
            parent !is PhpClass -> false
            !element.containingFile.isTestoFile() -> false
            else -> {
                val openBrace = PhpPsiUtil.getChildOfType(parent, PhpTokenTypes.chLBRACE)
                when {
                    openBrace != null && openBrace.textOffset <= element.textOffset -> true
                    else -> false
                }
            }
        }
    }
}