package com.github.xepozz.testo.util

import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.PhpYield
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor

class ExitStatementsVisitor(val myElement: PsiElement) : PhpElementVisitor() {
    var index = -1
    private var stop = false

    override fun visitPhpYield(element: PhpYield?) {
        if (stop) return
        index++
        super.visitPhpYield(element)
    }

    override fun visitPhpReturn(returnStatement: PhpReturn?) {
        if (stop) return
        index++
        super.visitPhpReturn(returnStatement)
    }

    override fun visitElement(element: PsiElement) {
        if (element == myElement) {
            stop = true
            return
        }
        element.acceptChildren(this)
    }
}
