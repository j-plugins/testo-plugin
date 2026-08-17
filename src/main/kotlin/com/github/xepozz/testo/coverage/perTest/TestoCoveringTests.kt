package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.coverage.format.TestId
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/** Covering tests in a stable order for lists and gutters: by class, then method. */
internal val TEST_ID_ORDER: Comparator<TestId> = compareBy({ it.fqcn }, { it.method })

/**
 * The tests that touched any line the [element] spans, from the project's per-test coverage — for a declaration, its
 * whole body. Empty when the element has no file/document, or nothing covered it.
 */
internal fun testsCoveringElement(element: PsiElement): Set<TestId> {
    val file = element.containingFile ?: return emptySet()
    val virtualFile = file.virtualFile ?: return emptySet()
    val project = element.project
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return emptySet()
    val range = element.textRange ?: return emptySet()
    // Report line numbers are 1-based; the document is 0-based.
    val first = document.getLineNumber(range.startOffset) + 1
    val last = document.getLineNumber(range.endOffset.coerceAtMost(document.textLength)) + 1
    return TestoCoverageByTestIndex.getInstance(project).data().testsCoveringRange(virtualFile.path, first..last)
}
