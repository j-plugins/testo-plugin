package com.github.xepozz.testo.util

import com.github.xepozz.testo.TestoClasses
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner

object PsiUtil {
    val DATA_PROVIDER_ATTRIBUTES = arrayOf(
        TestoClasses.DATA_PROVIDER,
        TestoClasses.DATA_SET,
    )
    val MEANINGFUL_ATTRIBUTES = arrayOf(
        TestoClasses.TEST,
        TestoClasses.TEST_INLINE,
        TestoClasses.DATA_PROVIDER,
        TestoClasses.DATA_SET,
    )

    fun getAttributeOrder(attribute: PhpAttribute, owner: PhpAttributesOwner): Int = owner
        .attributes
        .filter { it.fqn in MEANINGFUL_ATTRIBUTES }
        .indexOf(attribute)

    fun getExitStatementOrder(element: PsiElement, function: Function): Int = ExitStatementsVisitor(element)
        .apply { function.accept(this) }
        .index
}