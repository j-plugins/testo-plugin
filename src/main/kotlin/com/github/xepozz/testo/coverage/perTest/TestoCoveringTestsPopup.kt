package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.TestId
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import javax.swing.Icon

/** `\Ns\FooTest::method` as a list shows it: the class alone, since the namespace is the same for every row. */
internal fun shortTestLabel(id: TestId): String = "${id.fqcn.trimStart('\\').substringAfterLast('\\')}::${id.method}"

/**
 * The list behind the *Run covering tests* gutter icon: all of them at the top, then one row per test. A click on a
 * declaration should not fire a whole test run before the user has seen what it is about to run.
 */
internal object TestoCoveringTestsPopup {

    private class Row(val test: TestId?, val label: String, val icon: Icon)

    fun show(project: Project, tests: List<TestId>, subject: String, editor: Editor?, at: RelativePoint?) {
        if (tests.isEmpty()) return
        val rows = buildList {
            add(Row(null, TestoBundle.message("testo.coverage.editor.popup.run.all", tests.size), AllIcons.Actions.RunAll))
            tests.forEach { add(Row(it, shortTestLabel(it), AllIcons.Toolwindows.ToolWindowRunWithCoverage)) }
        }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setTitle(TestoBundle.message("testo.coverage.popup.title", subject))
            .setRenderer(SimpleListCellRenderer.create<Row> { label, row, _ ->
                label.text = row.label
                label.icon = row.icon
            })
            .setItemChosenCallback { row ->
                val chosen = row.test?.let { listOf(it) } ?: tests
                val name = row.test?.let { shortTestLabel(it) } ?: subject
                TestoCoveringTestsLauncher.run(project, chosen, TestoCoveringTestsLauncher.runName(name, chosen.size))
            }
            .createPopup()
        if (at != null) popup.show(at) else if (editor != null) popup.showInBestPositionFor(editor)
    }
}
