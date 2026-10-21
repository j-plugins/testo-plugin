package com.github.xepozz.testo.tests.inspections

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement

class TestoInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, inspectionId: String): Boolean {
        if (inspectionId !in UNHANDLED_EXCEPTION_IDS) return false
        if (!element.containingFile.isTestoFile()) return false

        return TestoPhp.getInstance().callThrows(element.parent, TestoClasses.ASSERTION_EXCEPTION)
    }

    override fun getSuppressActions(
        element: PsiElement?,
        inspectionId: String
    ): Array<out SuppressQuickFix> = emptyArray()

    private companion object {
        // PhpStorm's id and PHP for OpenIDE's.
        val UNHANDLED_EXCEPTION_IDS = setOf("PhpUnhandledExceptionInspection", "PhpUnhandledException")
    }
}