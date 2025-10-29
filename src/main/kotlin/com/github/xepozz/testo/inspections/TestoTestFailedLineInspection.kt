package com.github.xepozz.testo.inspections

import com.github.xepozz.testo.services.TestoFailedLineManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.lang.inspections.PhpTestFailedLineInspectionBase
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor

class TestoTestFailedLineInspection : PhpTestFailedLineInspectionBase() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PhpElementVisitor() {
            override fun visitPhpMethodReference(reference: MethodReference) {
                val failedLineManager = holder.project.getService(TestoFailedLineManager::class.java)
                process(holder, reference, failedLineManager)
            }
        }
    }
}