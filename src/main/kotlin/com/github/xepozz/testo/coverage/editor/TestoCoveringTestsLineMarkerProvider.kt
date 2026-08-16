package com.github.xepozz.testo.coverage.editor

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.perTest.TestoCoverageByTestIndex
import com.github.xepozz.testo.coverage.perTest.TestoCoveringTestsPopup
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.ui.awt.RelativePoint
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement

/**
 * A gutter icon on every method, function and class the per-test coverage recorded as covered: *Run covering tests (N)*,
 * launching exactly those tests again, with coverage.
 *
 * Only `coverage-xml` carries which test touched which line, so the icon appears only after such a report is loaded
 * ([TestoCoverageByTestIndex]) — and only while the Coverage view's toggle for it is on.
 */
class TestoCoveringTestsLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.elementType != PhpTokenTypes.IDENTIFIER) return null
        val owner = element.parent
        if (owner !is Function && owner !is PhpClass) return null
        owner as PhpNamedElement
        // The declaration's own name, and nothing else that parses as an identifier under it — otherwise one
        // declaration can be marked twice.
        if (owner.nameNode?.psi !== element) return null
        val project = element.project
        if (!TestoCoveringTestsGutter.getInstance(project).enabled) return null

        val tests = coveringTests(owner).sortedBy { "${it.fqcn}::${it.method}" }
        if (tests.isEmpty()) return null
        val label = TestoBundle.message("testo.coverage.gutter.run.covering", tests.size)
        val subject = owner.name

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Toolwindows.ToolWindowRunWithCoverage,
            { label },
            { event, _ ->
                TestoCoveringTestsPopup.show(project, tests, subject, RelativePoint(event))
            },
            GutterIconRenderer.Alignment.LEFT,
            { label },
        )
    }

    /** The tests that touched any line of the declaration — for a class, the union over everything it holds. */
    private fun coveringTests(owner: PhpNamedElement): Set<TestId> {
        val file = owner.containingFile ?: return emptySet()
        val virtualFile = file.virtualFile ?: return emptySet()
        val data = TestoCoverageByTestIndex.getInstance(owner.project).data()
        val document = PsiDocumentManager.getInstance(owner.project).getDocument(file) ?: return emptySet()
        val range = owner.textRange ?: return emptySet()
        // Report line numbers are 1-based; the document is 0-based.
        val first = document.getLineNumber(range.startOffset) + 1
        val last = document.getLineNumber(range.endOffset.coerceAtMost(document.textLength)) + 1
        return data.testsCoveringRange(virtualFile.path, first..last)
    }
}

/** The user's switch for those gutter icons, off the Coverage view's toolbar. Per project, remembered. */
@Service(Service.Level.PROJECT)
class TestoCoveringTestsGutter(private val project: Project) {

    var enabled: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(KEY, true)
        set(value) {
            PropertiesComponent.getInstance(project).setValue(KEY, value, true)
            // The markers are computed by the daemon, which has no reason of its own to rerun: no file changed.
            DaemonCodeAnalyzer.getInstance(project).restart()
        }

    companion object {
        private const val KEY = "testo.coverage.gutter.coveringTests"

        fun getInstance(project: Project): TestoCoveringTestsGutter =
            project.getService(TestoCoveringTestsGutter::class.java)
    }
}
