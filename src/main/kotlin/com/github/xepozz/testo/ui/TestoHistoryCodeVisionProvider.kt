package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.runs.replayNewestRun
import com.github.xepozz.testo.runs.replayNewestRunWithTest
import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.github.xepozz.testo.tests.console.TestoHistoryIndex
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SyntaxTraverser
import com.jetbrains.php.lang.psi.elements.Function
import java.awt.event.MouseEvent

/**
 * Code Vision lens shown next to every Testo test method/function in the PHP editor,
 * right where the green gutter run icons live.
 *
 * The lens reads "Show history" and is shown only for tests the run archive
 * ([com.github.xepozz.testo.runs.TestoRunStore]) holds; clicking it replays the newest archived run containing that
 * test into a full Testo run tab. The pass/total (N/M) count is intentionally NOT computed yet — see [historyHint].
 */
class TestoHistoryCodeVisionProvider : CodeVisionProviderBase() {

    override val id: String = "testo.history"

    override val name: String = "Testo test history"

    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    override val defaultAnchor: CodeVisionAnchorKind
        get() = CodeVisionAnchorKind.Default

    /** Cheap gate: only PHP files recognized as Testo test files. */
    override fun acceptsFile(file: PsiFile): Boolean = file.isTestoFile()

    /** Attach to the Testo test declaration (method/function/benchmark), not the name leaf. */
    override fun acceptsElement(element: PsiElement): Boolean =
        element is Function && element.isTestoExecutable()

    override fun getHint(element: PsiElement, file: PsiFile): String? {
        val function = element as? Function ?: return null
        val url = TestoTestRunLineMarkerProvider.getLocationHint(function)
        // Show the lens only for a test some archived run can replay.
        if (!TestoHistoryIndex.contains(file.project, url)) return null
        return historyHint(url)
    }

    /**
     * The lens label for a test that has an archived run.
     *
     * v1 returns the plain "Show history" action label. This is the single hook to enable the N/M (passed/total)
     * count later: the archive already carries each run's per-status tally, so this could read the newest run holding
     * [url] and return something like "$passed/$total passed — Show history". Returning null hides the lens.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun historyHint(url: String): String? = "Show history"

    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
        val function = element as? Function ?: return openLatestHistory(element.project)
        val url = TestoTestRunLineMarkerProvider.getLocationHint(function)
        // The most recent run that actually holds this test, not merely the globally latest one.
        replayNewestRunWithTest(element.project, url)
    }

    /**
     * Override the base implementation only to decorate the entry with the Testo icon and a
     * tooltip. The traversal/acceptance/click wiring mirrors [CodeVisionProviderBase].
     */
    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file.project.isDefault) return emptyList()
        if (!acceptsFile(file)) return emptyList()

        val lenses = ArrayList<Pair<TextRange, CodeVisionEntry>>()
        for (element in SyntaxTraverser.psiTraverser(file)) {
            if (!acceptsElement(element)) continue
            if (!InlayHintsUtils.isFirstInLine(element)) continue
            val hint = getHint(element, file) ?: continue

            val pointer = SmartPointerManager.createPointer(element)
            val onClick: (MouseEvent?, Editor) -> Unit = { event, clickEditor ->
                pointer.element?.let { handleClick(clickEditor, it, event) }
            }
            val range = InlayHintsUtils.getTextRangeWithoutLeadingCommentsAndWhitespaces(element)
            lenses.add(
                range to ClickableTextCodeVisionEntry(
                    hint,
                    id,
                    onClick,
                    TestoIcons.TESTO,
                    hint,
                    "Replay the newest archived run containing this test",
                )
            )
        }
        return lenses
    }

    companion object {
        /** Re-open the most recent archived Testo run in the Run tool window. */
        fun openLatestHistory(project: Project) = replayNewestRun(project)
    }
}

// Refresh note: this provider is DaemonBound, so lenses recompute with the daemon — which a test run never triggers
// (it touches no PHP source). TestoRunArchiver forces the pass through TestoHistoryIndex.refreshLens the moment a run
// is archived, which is what makes a just-run test's lens appear without an IDE restart.
