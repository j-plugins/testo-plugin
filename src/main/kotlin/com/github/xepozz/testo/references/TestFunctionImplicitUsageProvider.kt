package com.github.xepozz.testo.references

import com.github.xepozz.testo.isTesto
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiElement

class TestFunctionImplicitUsageProvider : ImplicitUsageProvider {
    override fun isImplicitUsage(element: PsiElement) = element.isTesto()

    override fun isImplicitRead(element: PsiElement) = false

    override fun isImplicitWrite(element: PsiElement) = false
}