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
        TestoClasses.DATA_ATTRIBUTES,
    )

    fun getAttributeGroup(fqn: String?): Array<String>? {
        if (fqn == null) return null
        val group = ATTRIBUTE_GROUPS.firstOrNull { fqn in it }
        if (group != null) return group
        if (fqn in MEANINGFUL_ATTRIBUTES && fqn != TestoClasses.TEST) return arrayOf(fqn)
        return null
    }

    fun getAttributeOrder(attribute: PhpAttribute, owner: PhpAttributesOwner): Int {
        val fqn = attribute.fqn ?: return -1
        val group = getAttributeGroup(fqn) ?: return -1
        return owner.attributes
            .filter { it.fqn in group }
            .indexOf(attribute)
    }

    fun getExitStatementOrder(element: PsiElement, function: Function): Int = ExitStatementsVisitor(element)
        .apply { function.accept(this) }
        .index
}
