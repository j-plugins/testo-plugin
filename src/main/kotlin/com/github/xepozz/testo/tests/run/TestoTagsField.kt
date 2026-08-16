package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder

/**
 * A list of names shown as removable tags. A suite or group name is opaque to the toolchain — whatever the CLI is
 * handed is what it selects — so a free-form field had no separator it could safely own; tags remove the question.
 *
 * Names either come from a popup of what the project declares ([suggestions]) or are typed into an inline field.
 */
class TestoTagsField(
    private val emptyLabel: String,
    private val addTooltip: String,
    /** What the add button offers; null puts an inline field there instead, where Enter adds what was typed. */
    private val suggestions: (() -> List<String>)? = null,
) : JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))) {

    private val listeners = mutableListOf<() -> Unit>()

    /** One instance, re-added on every rebuild: a fresh field would lose the focus after each Enter. */
    private val input: JBTextField? = if (suggestions == null) inlineInput() else null

    var names: List<String> = emptyList()
        set(value) {
            field = value.distinct()
            rebuild()
        }

    init {
        isOpaque = false
        rebuild()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun set(value: List<String>) {
        names = value
        listeners.forEach { it() }
    }

    private fun rebuild() {
        removeAll()
        if (names.isEmpty() && input == null) add(JBLabel(emptyLabel).apply { foreground = JBColor.GRAY })
        names.forEach { add(chip(it)) }
        add(input ?: addButton())
        revalidate()
        repaint()
    }

    private fun chip(name: String): Component = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
        isOpaque = false
        border = CompoundBorder(
            RoundedLineBorder(JBColor.border(), JBUI.scale(12)),
            JBUI.Borders.empty(0, 6, 0, 2),
        )
        add(JBLabel(name))
        add(
            InplaceButton(
                IconButton(
                    TestoBundle.message("testo.tags.remove", name),
                    AllIcons.Actions.Close,
                    AllIcons.Actions.CloseHovered,
                ),
            ) { set(names - name) },
        )
    }

    private fun addButton(): Component {
        lateinit var button: InplaceButton
        button = InplaceButton(IconButton(addTooltip, AllIcons.General.Add)) { showSuggestions(button) }
        return button
    }

    /** The no-suggestions half: a name is typed and Enter turns it into a tag, ready for the next one. */
    private fun inlineInput(): JBTextField = JBTextField(INPUT_COLUMNS).apply {
        emptyText.text = addTooltip
        addActionListener {
            val name = text.trim()
            if (name.isEmpty()) return@addActionListener
            text = ""
            set(names + name)
            SwingUtilities.invokeLater { requestFocusInWindow() }
        }
    }

    private fun showSuggestions(anchor: Component) {
        val known = suggestions?.invoke().orEmpty().filterNot { it in names }
        val rows = known.map { Row(it) } + Row(null)

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(rows)
            .setTitle(addTooltip)
            .setRenderer(SimpleListCellRenderer.create<Row> { label, row, _ ->
                label.text = row.name ?: TestoBundle.message("testo.tags.custom")
                label.icon = if (row.name == null) AllIcons.General.Add else null
            })
            .setItemChosenCallback { row ->
                val name = row.name ?: askForName()
                if (!name.isNullOrBlank()) set(names + name.trim())
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun askForName(): String? = Messages.showInputDialog(
        this,
        TestoBundle.message("testo.tags.custom.prompt"),
        addTooltip,
        null,
    )

    /** A suggestion, or — with no name — the row that asks for one the index has never seen. */
    private class Row(val name: String?)

    private companion object {
        const val INPUT_COLUMNS = 14
    }
}
