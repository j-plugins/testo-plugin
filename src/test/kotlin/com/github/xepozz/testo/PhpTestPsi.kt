package com.github.xepozz.testo

import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpElementView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser

/*
 * PHP code of a test file found through TestoPhp, so the tests of the core run against whichever PHP implementation
 * is installed. Everything is in document order and excludes the element searched from, like PsiTreeUtil's
 * findChildrenOfType.
 */

/** The name the fixture gives a file it creates from the PHP file type. */
const val PHP_TEST_FILE = "aaa.php"

private val php get() = TestoPhp.getInstance()

private fun PsiElement.descendants(): List<PsiElement> =
    SyntaxTraverser.psiTraverser(this).preOrderDfsTraversal().filter { it !== this }.toList()

private fun PsiElement.phpViews(): List<PhpElementView> = descendants().mapNotNull { php.view(it) }

fun PsiElement.phpClasses(): List<PhpClassView> = phpViews().filterIsInstance<PhpClassView>()

/** Free functions and methods alike. */
fun PsiElement.phpFunctions(): List<PhpFunctionView> = phpViews().filterIsInstance<PhpFunctionView>()

fun PsiElement.phpMethods(): List<PhpFunctionView> = phpFunctions().filter { it.isMethod }

fun PsiElement.phpAttributes(): List<PhpAttributeView> = phpViews().filterIsInstance<PhpAttributeView>()

fun PsiElement.phpYields(): List<PsiElement> = descendants().filter { php.isYield(it) }

fun PsiElement.phpReturns(): List<PsiElement> = descendants().filter { php.isExitStatement(it) && !php.isYield(it) }
