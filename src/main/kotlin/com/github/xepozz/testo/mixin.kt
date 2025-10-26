package com.github.xepozz.testo

import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method

fun PsiElement.isTesto(): Boolean {
    return when (this) {
        is Function -> this.getAttributes(TestoClasses.TEST).isNotEmpty()
        else -> false
    }
}