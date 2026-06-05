package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.isTestoExecutable
import com.github.xepozz.testo.isTestoFile
import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.execution.TestStateStorage
import com.intellij.execution.testframework.sm.TestHistoryConfiguration
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SyntaxTraverser
import com.jetbrains.php.lang.psi.elements.Function
import java.awt.event.MouseEvent
import java.io.File

/**
 * Code Vision lens shown next to every Testo test method/function in the PHP editor,
 * right where the green gutter run icons live.
 *
 * v1: the lens reads "Show history" and is shown only for tests that already have a stored
 * run result. Clicking it re-opens the latest Testo test-run history session in the Run
 * tool window. The pass/total (N/M) count is intentionally NOT computed yet — see [historyHint].
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
        // Only show the lens when there is a prior run result for this test.
        if (TestStateStorage.getInstance(file.project).getState(url) == null) return null
        return historyHint(url)
    }

    /**
     * The lens label for a test that has stored history.
     *
     * v1 returns the plain "Show history" action label. This is the single hook to enable the
     * N/M (passed/total) count later: read aggregated results for [url] (e.g. via
     * [TestStateStorage] / the imported SMTRunner tree) and return something like
     * "$passed/$total passed — Show history". Returning null hides the lens.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun historyHint(url: String): String? = "Show history"

    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
        openLatestHistory(element.project)
        // TODO(follow-up): after the imported tree is built, select the specific test node via
        //  TestResultsViewer.selectAndNotify matching SMTestProxy.getLocationUrl() == the test url.
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
                    "Open the latest test run history",
                )
            )
        }
        return lenses
    }

    companion object {
        /**
         * Re-open the most recent Testo test-run history session in the Run tool window.
         *
         * Mirrors [com.intellij.execution.testframework.sm.runner.history.actions.ImportTestsGroup]:
         * resolve every recorded history file under the project history root, keep the existing
         * ones, and import the most recently modified XML. openTestoHistory recreates the run
         * configuration from the XML and opens the SM test tree tab (on our own console properties).
         */
        fun openLatestHistory(project: Project) {
            val historyRoot = TestStateStorage.getTestHistoryRoot(project)
            val latest = TestHistoryConfiguration.getInstance(project).files
                .map { File(historyRoot, it) }
                .filter { it.exists() }
                .maxByOrNull { it.lastModified() }
                ?: return // No history yet — getHint already hid the lens, so this is just defensive.

            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(latest) ?: return
            // Build the imported console on our own properties so its toolbar matches a live run (see openTestoHistory).
            com.github.xepozz.testo.tests.console.openTestoHistory(project, virtualFile)
        }
    }
}

// Refresh note: this provider is DaemonBound, so lenses recompute with the daemon. To refresh
// immediately after a run finishes (so the lens appears as soon as the first result is stored),
// subscribe to SMTRunnerEventsListener.TEST_STATUS / run completion and call
// DaemonCodeAnalyzer.getInstance(project).restart(). Skipped in v1 to keep the change small.
