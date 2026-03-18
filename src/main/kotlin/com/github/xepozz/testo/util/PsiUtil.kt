package com.github.xepozz.testo.util

import com.github.xepozz.testo.TestoClasses
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner

object PsiUtil {
    val MEANINGFUL_ATTRIBUTES = arrayOf(
        *TestoClasses.DATA_ATTRIBUTES,
        *TestoClasses.TEST_ATTRIBUTES,
        *TestoClasses.BENCH_ATTRIBUTES,
    )

    val ATTRIBUTE_GROUPS: Array<Array<String>> = arrayOf(
        TestoClasses.TEST_DATA_ATTRIBUTES,
        TestoClasses.TEST_INLINE_ATTRIBUTES,
        TestoClasses.BENCH_ATTRIBUTES,
    )

    fun getAttributeGroup(fqn: String?): Array<String>? =
        ATTRIBUTE_GROUPS.firstOrNull { fqn in it }

    fun getAttributeOrder(attribute: PhpAttribute, owner: PhpAttributesOwner): Int {
        val group = getAttributeGroup(attribute.fqn) ?: return -1
        return owner.attributes
            .filter { it.fqn in group }
            .indexOf(attribute)
    }

    fun getExitStatementOrder(element: PsiElement, function: Function): Int = ExitStatementsVisitor(element)
        .apply { function.accept(this) }
        .index
}
