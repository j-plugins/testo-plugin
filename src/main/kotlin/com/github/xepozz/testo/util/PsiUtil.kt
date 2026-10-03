package com.github.xepozz.testo.util

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.PhpDeclarationView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.psi.PsiElement

object PsiUtil {
    val MEANINGFUL_ATTRIBUTES = arrayOf(
        *TestoClasses.DATA_ATTRIBUTES,
        *TestoClasses.TEST_ATTRIBUTES,
        *TestoClasses.BENCH_ATTRIBUTES,
        *TestoClasses.TEST_CASE_ATTRIBUTES,
    )

    val ATTRIBUTE_GROUPS: Array<Array<String>> = arrayOf(
        TestoClasses.DATA_ATTRIBUTES,
        TestoClasses.TEST_INLINE_ATTRIBUTES,
        TestoClasses.BENCH_ATTRIBUTES,
    )

    fun getAttributeGroup(fqn: String?): Array<String>? =
        ATTRIBUTE_GROUPS.firstOrNull { fqn in it }

    /** The position of [attribute] among the attributes of its own group on [owner], or -1 for an unnumbered one. */
    fun getAttributeOrder(attribute: PsiElement, owner: PsiElement): Int {
        val php = TestoPhp.getInstance()
        val view = php.view(attribute) as? PhpAttributeView ?: return -1
        val group = getAttributeGroup(view.fqn) ?: return -1
        val declaration = php.view(owner) as? PhpDeclarationView ?: return -1
        return declaration.attributes
            .filter { it.fqn in group }
            .indexOf(view)
    }

    fun getExitStatementOrder(element: PsiElement, function: PsiElement): Int = ExitStatementsVisitor(element)
        .apply { function.accept(this) }
        .index
}
