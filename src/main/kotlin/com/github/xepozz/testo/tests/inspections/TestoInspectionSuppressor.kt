package com.github.xepozz.testo.tests.inspections

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.isTestoFile
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.MethodReference

class TestoInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, inspectionId: String): Boolean {
        if (inspectionId != "PhpUnhandledExceptionInspection") return false
        if (!element.containingFile.isTestoFile()) return false

        val methodReference = element.parent as? MethodReference ?: return false

        return methodReference.multiResolve(false)
            .mapNotNull { it.element as? Function }
            .any { function ->
                function
                    .docComment
                    ?.exceptionClasses
                    ?.any { exceptionClassType ->
                        exceptionClassType
                            .types
                            .any { it == TestoClasses.ASSERTION_EXCEPTION }
                    }
                    ?: false
            }
    }

    override fun getSuppressActions(
        element: PsiElement?,
        inspectionId: String
    ): Array<out SuppressQuickFix> = emptyArray()
}