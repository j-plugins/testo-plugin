package com.github.xepozz.testo.util

import com.github.xepozz.testo.TestoClasses
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner

object PsiUtil {
    val MEANINGFUL_ATTRIBUTES = arrayOf(
        TestoClasses.TEST_OLD,
        TestoClasses.TEST_NEW,
        TestoClasses.TEST_INLINE_OLD,
        TestoClasses.TEST_INLINE_NEW,
        TestoClasses.DATA_PROVIDER_OLD,
        TestoClasses.DATA_PROVIDER_NEW,
        TestoClasses.DATA_SET_OLD,
        TestoClasses.DATA_SET_NEW,
        TestoClasses.DATA_UNION,
        TestoClasses.DATA_CROSS,
        TestoClasses.DATA_ZIP,
        TestoClasses.BENCH_WITH,
    )

    fun getAttributeOrder(attribute: PhpAttribute, owner: PhpAttributesOwner): Int = owner
        .attributes
        .filter { it.fqn in MEANINGFUL_ATTRIBUTES }
        .indexOf(attribute)

    fun getExitStatementOrder(element: PsiElement, function: Function): Int = ExitStatementsVisitor(element)
        .apply { function.accept(this) }
        .index
}