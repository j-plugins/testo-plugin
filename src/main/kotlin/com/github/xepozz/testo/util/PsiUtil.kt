package com.github.xepozz.testo.util

import com.github.xepozz.testo.TestoClasses
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner

object PsiUtil {
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
}