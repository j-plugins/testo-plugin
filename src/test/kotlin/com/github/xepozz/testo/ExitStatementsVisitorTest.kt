package com.github.xepozz.testo

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.github.xepozz.testo.util.ExitStatementsVisitor
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.PhpYield

class ExitStatementsVisitorTest : BasePlatformTestCase() {

    fun testVisitor_countsYieldStatements() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class Foo {
                public static function data(): iterable {
                    yield [1, 2];
                    yield [3, 4];
                    yield [5, 6];
                }
            }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val yields = PsiTreeUtil.findChildrenOfType(method, PhpYield::class.java).toList()

        assertEquals("Should find 3 yield statements", 3, yields.size)

        // Visit up to the last yield - index should be 2 (0-based, incremented before stop)
        val visitor = ExitStatementsVisitor(yields.last())
        method.accept(visitor)
        assertEquals("Index should be 2 for the third yield (0-indexed)", 2, visitor.index)
    }

    fun testVisitor_countsReturnStatements() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class Foo {
                public static function data(): array {
                    return [1, 2];
                }
            }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val returns = PsiTreeUtil.findChildrenOfType(method, PhpReturn::class.java).toList()

        assertEquals("Should find 1 return statement", 1, returns.size)

        val visitor = ExitStatementsVisitor(returns.first())
        method.accept(visitor)
        assertEquals("Index should be 0 for the first return", 0, visitor.index)
    }

    fun testVisitor_stopsAtTargetElement() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class Foo {
                public static function data(): iterable {
                    yield [1];
                    yield [2];
                    yield [3];
                }
            }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val yields = PsiTreeUtil.findChildrenOfType(method, PhpYield::class.java).toList()

        // Stop at the second yield
        val visitor = ExitStatementsVisitor(yields[1])
        method.accept(visitor)
        assertEquals("Index should be 1 when stopping at second yield", 1, visitor.index)
    }

    fun testVisitor_firstYield() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class Foo {
                public static function data(): iterable {
                    yield [1];
                    yield [2];
                }
            }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val yields = PsiTreeUtil.findChildrenOfType(method, PhpYield::class.java).toList()

        val visitor = ExitStatementsVisitor(yields[0])
        method.accept(visitor)
        assertEquals("Index should be 0 for the first yield", 0, visitor.index)
    }

    fun testVisitor_initialIndex() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php function noop(): void {}"""
        )
        val function = PsiTreeUtil.findChildrenOfType(psiFile, Function::class.java)
            .first { it !is Method }
        val visitor = ExitStatementsVisitor(function)
        assertEquals("Initial index should be -1", -1, visitor.index)
    }
}
