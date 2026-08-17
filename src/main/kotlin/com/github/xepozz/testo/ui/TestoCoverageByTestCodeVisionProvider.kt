package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.perTest.TEST_ID_ORDER
import com.github.xepozz.testo.coverage.perTest.TestoTestIdentityMapper
import com.github.xepozz.testo.coverage.perTest.shortTestLabel
import com.github.xepozz.testo.coverage.perTest.testsCoveringElement
import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import java.awt.event.MouseEvent

/**
 * Code Vision lens on any PHP method/function that Testo's per-test coverage recorded as covered, reading the
 * persistent [TestoCoverageByTestIndex]. Shows "N covering tests"; clicking lists them and navigates to the chosen one.
 *
 * This is the public-API answer to "how many tests cover this": the native `CoverageEngine.getTestsForLine`
 * gutter is `@ApiStatus.Internal` and cannot be used by a third-party plugin, so the same data drives our own lens.
 * The lens is empty (hidden) until a `coverage-xml` coverage run has populated the index.
 */
class TestoCoverageByTestCodeVisionProvider : CodeVisionProviderBase() {

    override val id: String = "testo.coverage.byTest"

    override val name: String = TestoBundle.message("testo.coverage.byTest.name")

    override val relativeOrderings: List<CodeVisionRelativeOrdering> =
        listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    override val defaultAnchor: CodeVisionAnchorKind get() = CodeVisionAnchorKind.Default

    override fun acceptsFile(file: PsiFile): Boolean = file is PhpFile

    override fun acceptsElement(element: PsiElement): Boolean = element is Function

    override fun getHint(element: PsiElement, file: PsiFile): String? {
        val count = testsCoveringElement(element as? Function ?: return null).size
        return when (count) {
            0 -> null
            1 -> TestoBundle.message("testo.coverage.byTest.hint.one")
            else -> TestoBundle.message("testo.coverage.byTest.hint.many", count)
        }
    }

    override fun handleClick(editor: Editor, element: PsiElement, event: MouseEvent?) {
        val function = element as? Function ?: return
        val project = function.project
        val tests = testsCoveringElement(function).sortedWith(TEST_ID_ORDER)
        if (tests.isEmpty()) return

        val mapper = TestoTestIdentityMapper.getInstance()
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(tests)
            .setTitle(
                if (tests.size == 1) TestoBundle.message("testo.coverage.byTest.chooser.one")
                else TestoBundle.message("testo.coverage.byTest.chooser.many", tests.size)
            )
            .setRenderer(SimpleListCellRenderer.create<TestId>("") { shortTestLabel(it) })
            .setItemChosenCallback { id ->
                (mapper.resolve(id, project) as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
            }
            .createPopup()
        if (event != null) popup.show(RelativePoint(event)) else popup.showInBestPositionFor(editor)
    }

    // Mirror CodeVisionProviderBase's traversal but decorate the entry with the Testo icon and a tooltip (as the
    // "Show history" lens does), and route the click through handleClick.
    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        if (file.project.isDefault || !acceptsFile(file)) return emptyList()

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
                    TestoBundle.message("testo.coverage.byTest.tooltip"),
                )
            )
        }
        return lenses
    }
}
