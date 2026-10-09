package com.github.xepozz.testo.phpstorm

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement

/**
 * The views read the same PhpStorm accessors the plugin read before they existed, even where PhpStorm offers two: a
 * class's `isAbstract`/`isFinal` against its modifier, a declaration's name identifier against its name node.
 */
class PhpStormViewsTest : BasePlatformTestCase() {

    fun testClassFlagsAgreeWithTheModifier() {
        val file = myFixture.configureByText(
            "kinds.php",
            """<?php
            class Plain {}
            abstract class Base {}
            final class Leaf {}
            interface Contract {}
            trait Mixin {}
            enum Kind {}
            """.trimIndent(),
        )
        for (cls in PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java)) {
            val view = PhpStormClass(cls)
            assertEquals("isAbstract of ${cls.name}", cls.modifier.isAbstract, view.isAbstract)
            assertEquals("isFinal of ${cls.name}", cls.modifier.isFinal, view.isFinal)
        }
    }

    fun testNameIdentifierIsTheNameNode() {
        val file = myFixture.configureByText(
            "names.php",
            "<?php class C { public function m() {} } function f() {}",
        )
        val named = PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java) +
            PsiTreeUtil.findChildrenOfType(file, Function::class.java)
        for (element in named) {
            element as PhpNamedElement
            val view = PhpStormTestoPhp.viewOf(element) as com.github.xepozz.testo.php.PhpDeclarationView
            assertSame("name identifier of ${element.name}", element.nameNode?.psi, view.nameIdentifier)
        }
    }
}
