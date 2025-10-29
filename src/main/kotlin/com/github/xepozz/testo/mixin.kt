package com.github.xepozz.testo

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

fun PsiElement.isTestoMethod() = when {
    this !is Function -> false
    getAttributes(TestoClasses.TEST).isNotEmpty() -> true
    this is Method && modifier.isPublic && name.startsWith("test") -> true
    else -> false
}

fun PsiElement.isTestoClass() = when (this) {
    is PhpClass -> name.endsWith("Test") || methods.any { it.isTestoMethod() }
    else -> false
}

fun PsiFile.isTestoFile() = when (this) {
    is PhpFile -> name.endsWith("Test") ||
            PsiTreeUtil.findChildrenOfType(this, PhpClass::class.java)
                .any { it.isTestoClass() } ||
            PsiTreeUtil.findChildrenOfType(this, Function::class.java)
                .any { it.isTestoMethod() }

    else -> false
}

fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean) = sequence {
    with(iterator()) {
        while (hasNext()) {
            val next = next()
            yield(next)
            if (!predicate(next)) break
        }
    }
}

fun <T> Collection<T>.takeWhileInclusive(predicate: (T) -> Boolean): Collection<T> =
    this.asSequence().takeWhileInclusive(predicate).toList()