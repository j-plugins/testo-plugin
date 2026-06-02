package com.github.xepozz.testo.tests.console

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

/**
 * `Tools | Testo | Channel Icons` (also reachable via Find Action) — opens a modal table previewing every
 * channel `icon='...'` hint and the IDE icon it maps to (see [ChannelIcons]).
 */
class TestoShowChannelIconsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ChannelIconsDialog(e.project).show()
    }

    private class ChannelIconsDialog(project: Project?) : DialogWrapper(project, false) {
        init {
            title = "Testo Channel Icons"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val rows = ChannelIcons.MAP.entries
                .map { arrayOf<Any>(it.key, it.value, AllIcons.Actions.Copy) }
                .toTypedArray()
            val model = object : DefaultTableModel(rows, arrayOf("Name", "Icon", "")) {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
                override fun getColumnClass(column: Int): Class<*> =
                    if (column == 0) String::class.java else Icon::class.java
            }
            val table = JBTable(model).apply {
                rowHeight = JBUI.scale(24)
                setShowGrid(false)
                tableHeader.reorderingAllowed = false
                columnModel.getColumn(1).apply { preferredWidth = JBUI.scale(64); maxWidth = JBUI.scale(64) }
                columnModel.getColumn(COPY_COLUMN).apply { preferredWidth = JBUI.scale(32); maxWidth = JBUI.scale(32) }
            }
            table.rowSorter = TableRowSorter(model).apply {
                setSortable(1, false) // icon columns aren't comparable
                setSortable(COPY_COLUMN, false)
                sortKeys = listOf(RowSorter.SortKey(0, SortOrder.ASCENDING)) // sort by name by default
            }
            table.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (table.columnAtPoint(e.point) != COPY_COLUMN) return
                    val row = table.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                    (table.getValueAt(row, 0) as? String)?.let {
                        CopyPasteManager.getInstance().setContents(StringSelection(it))
                    }
                }
            })
            // Hand cursor over the copy column so it reads as a button.
            table.addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val overCopy = table.columnAtPoint(e.point) == COPY_COLUMN && table.rowAtPoint(e.point) >= 0
                    table.cursor = Cursor.getPredefinedCursor(if (overCopy) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
                }
            })
            return JBScrollPane(table).apply { preferredSize = JBUI.size(380, 520) }
        }

        // Preview only — a single close button.
        override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)

        private companion object {
            const val COPY_COLUMN = 2
        }
    }
}
