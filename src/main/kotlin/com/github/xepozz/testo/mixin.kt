package com.github.xepozz.testo

import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpClass

fun PsiElement.isTestoMethod(): Boolean {
    return when (this) {
        is Function -> this.getAttributes(TestoClasses.TEST).isNotEmpty()
        else -> false
    }
}

fun PsiElement.isTestoClass(): Boolean {
    return when (this) {
        is PhpClass -> name.endsWith("Test") || methods.any { it.isTestoMethod() }
        else -> false
    }
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