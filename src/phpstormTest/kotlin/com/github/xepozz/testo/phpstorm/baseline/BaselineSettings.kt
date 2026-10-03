package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfigurationType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager

object BaselineSettings {
    /** The framework settings the producer gate reads are project state, so each test clears what it added. */
    fun clearFrameworkSettings(project: Project) {
        val manager = PhpTestFrameworkSettingsManager.getInstance(project)
        manager.setConfigurations(TestoFrameworkType.INSTANCE, emptyList())
    }

    fun newConfiguration(project: Project, name: String = "Testo"): TestoRunConfiguration =
        TestoRunConfigurationType.INSTANCE.createTemplateConfiguration(project).also { it.name = name }

    fun describe(element: PsiElement?): String = when (element) {
        null -> "<none>"
        is PsiFileSystemItem -> "${element.javaClass.simpleName}(${element.name})"
        else -> "${element.javaClass.simpleName}(${element.text.lineSequence().first().trim().take(60)})"
    }
}
