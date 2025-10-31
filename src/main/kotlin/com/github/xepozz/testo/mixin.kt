package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestDescriptor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner
import com.jetbrains.php.lang.psi.elements.PhpClass

fun PsiElement.isTestoExecutable() = isTestoFunction() || isTestoMethod()

fun PsiElement.isTestoFunction() = when {
    this is Function -> hasAttribute(TestoClasses.TEST)
    else -> false
}

fun PsiElement.isTestoMethod() = when {
    this is Method -> modifier.isPublic && name.startsWith("test") || hasAttribute(TestoClasses.TEST)
    else -> false
}

fun PsiElement.isTestoDataProvider() = when {
    this is Method -> modifier.isPublic && modifier.isStatic
    else -> false
}

fun PhpAttributesOwner.hasAttribute(fqn: String) = getAttributes(fqn).isNotEmpty()

fun PsiElement.isTestoClass() = when (this) {
    is PhpClass -> TestoTestDescriptor.isTestClassName(name) || methods.any { it.isTestoMethod() }
    else -> false
}

fun PsiFile.isTestoFile() = when (this) {
    is PhpFile -> TestoTestDescriptor.isTestClassName(name) || isTestoClassFile() || isTestoFunctionFile()
    else -> false
}

fun PhpFile.isTestoClassFile() = PsiTreeUtil.findChildrenOfType(this, PhpClass::class.java)
    .any { it.isTestoClass() }

fun PhpFile.isTestoFunctionFile() = PsiTreeUtil.findChildrenOfType(this, Function::class.java)
    .any { it.isTestoFunction() }

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