package com.github.xepozz.testo.util

import com.github.xepozz.testo.php.TestoPhp
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/**
 * Counts the `yield` and `return` statements met before [myElement] (itself included) in a depth-first walk, so
 * [index] is the element's position among the exit statements of the function the walk starts from.
 */
class ExitStatementsVisitor(val myElement: PsiElement) : PsiElementVisitor() {
    var index = -1
    private var stop = false
    private val php = TestoPhp.getInstance()

    override fun visitElement(element: PsiElement) {
        if (!stop && php.isExitStatement(element)) index++
        if (element == myElement) {
            stop = true
            return
        }
        element.acceptChildren(this)
    }
}
