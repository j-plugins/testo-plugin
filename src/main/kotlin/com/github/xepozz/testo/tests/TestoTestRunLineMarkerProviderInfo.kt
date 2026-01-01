package com.github.xepozz.testo.tests

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.psi.PsiElement
import java.util.function.Function
import javax.swing.Icon

class TestoTestRunLineMarkerProviderInfo(
    icon: Icon,
    actions: Array<AnAction>,
    tooltipProvider: Function<in PsiElement, String?>,
) : RunLineMarkerContributor.Info(icon, actions, tooltipProvider) {
    override fun shouldReplace(other: RunLineMarkerContributor.Info) = true
}