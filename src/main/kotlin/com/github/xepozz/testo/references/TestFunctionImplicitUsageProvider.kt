package com.github.xepozz.testo.references

import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.isTestoExecutable
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiElement

class TestFunctionImplicitUsageProvider : ImplicitUsageProvider {
    override fun isImplicitUsage(element: PsiElement) = element.isTestoExecutable() || element.isTestoClass()

    override fun isImplicitRead(element: PsiElement) = false

    override fun isImplicitWrite(element: PsiElement) = false

    override fun isClassWithCustomizedInitialization(element: PsiElement) = element.isTestoClass()
}