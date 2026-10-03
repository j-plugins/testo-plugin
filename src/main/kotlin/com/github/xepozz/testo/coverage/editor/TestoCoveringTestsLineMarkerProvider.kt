package com.github.xepozz.testo.coverage.editor

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.perTest.TEST_ID_ORDER
import com.github.xepozz.testo.coverage.perTest.TestoCoveringTestsPopup
import com.github.xepozz.testo.coverage.perTest.testsCoveringElement
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpDeclarationView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.PhpLeafKind
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import com.intellij.ui.awt.RelativePoint

/**
 * A gutter icon on every method, function and class the per-test coverage recorded as covered: *Run covering tests (N)*,
 * launching exactly those tests again, with coverage.
 *
 * Only `coverage-xml` carries which test touched which line, so the icon appears only after such a report is loaded
 * ([TestoCoverageByTestIndex]) — and only while the Coverage view's toggle for it is on.
 */
class TestoCoveringTestsLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val php = TestoPhp.getInstance()
        if (php.leafKind(element) != PhpLeafKind.IDENTIFIER) return null
        val owner = element.parent
        val declaration = php.view(owner) as? PhpDeclarationView ?: return null
        if (declaration !is PhpFunctionView && declaration !is PhpClassView) return null
        // The declaration's own name, and nothing else that parses as an identifier under it — otherwise one
        // declaration can be marked twice.
        if (declaration.nameIdentifier !== element) return null
        val project = element.project
        if (!TestoCoveringTestsGutter.getInstance(project).enabled) return null

        val tests = testsCoveringElement(owner).sortedWith(TEST_ID_ORDER)
        if (tests.isEmpty()) return null
        val label = TestoBundle.message("testo.coverage.gutter.run.covering", tests.size)
        val subject = declaration.name

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
