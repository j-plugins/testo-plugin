package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.TestoUtil
import com.github.xepozz.testo.isTestoFile
import com.intellij.ide.IconProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Iconable
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpClass
import javax.swing.Icon

class TestoIconProvider : IconProvider() {
    private val log = logger<TestoIconProvider>()

    override fun getIcon(element: PsiElement, @Iconable.IconFlags flags: Int): Icon? {
        return try {
            val phpFile = element as? PhpFile ?: return null
            val project = element.project
            if (!TestoUtil.isEnabled(project)) return null

            val virtualFile = phpFile.virtualFile ?: return null
            if (!virtualFile.isValid) return null

            val fileIndex = ProjectFileIndex.getInstance(project)
            // Quick exit: file is not in project content, is excluded, or is ignored by the IDE.
            if (!fileIndex.isInContent(virtualFile)) return null
            if (fileIndex.isExcluded(virtualFile)) return null
            if (fileIndex.isUnderIgnored(virtualFile)) return null

            if (!phpFile.isTestoFile()) return null
            if (!GlobalSearchScopesCore.projectTestScope(project).contains(virtualFile)) {
                return null
            }

            val phpClasses = PsiTreeUtil.findChildrenOfType(phpFile, PhpClass::class.java)

            when {
                phpClasses.isEmpty() -> TestoIcons.Layered.FUNCTION
                phpClasses.size > 1 -> TestoIcons.Layered.FILE
                phpClasses.first().modifier.isAbstract -> TestoIcons.Layered.Class.CLASS_ABSTRACT
                phpClasses.first().modifier.isFinal -> TestoIcons.Layered.Class.CLASS_FINAL
                else -> TestoIcons.Layered.Class.CLASS
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            log.warn("Failed to compute Testo icon for element: ${element.javaClass.name}", e)
            null
        }
    }
}
