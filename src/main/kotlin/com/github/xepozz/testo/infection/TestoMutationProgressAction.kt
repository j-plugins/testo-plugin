package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.JBColor
import com.intellij.ui.paint.LinePainter2D
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The mutation run of this tab's Testo run, at the right end of its toolbar: a ring while Infection works, the MSI
 * and the escaped count once it is done. A click brings up the *Mutations* tool window. Hidden until there is a run.
 */
class TestoMutationProgressAction(private val properties: TestoConsoleProperties) :
    AnAction(), CustomComponentAction, RightAlignedToolbarAction, DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent = Widget()

    private fun currentRun(): TestoMutationRun? {
        if (properties.project.isDisposed) return null
        val runDir = runCatching { properties.currentRunDir() }.getOrNull() ?: return null
        return TestoMutationService.getInstance(properties.project).runFor(runDir)
    }

    private inner class Widget : JComponent() {
        private val timer = Timer(TICK_MS) { tick() }
        private var run: TestoMutationRun? = null
        private var painted: String? = null
        private var spin = 0

        private var label = ""
        private var fraction = 0.0
        private var indeterminate = true
        private var verdict: Icon? = null
        private var escaped = 0

        override fun getFont(): Font = UIUtil.getLabelFont()

        init {
            isOpaque = false
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    run?.let { TestoMutationToolWindow.show(properties.project, it) }
                }
            })
        }

        override fun addNotify() {
            super.addNotify()
            timer.start()
            tick()
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

        private fun tick() {
            val current = currentRun()
            run = current
            if (current == null) {
                if (isVisible) {
                    isVisible = false
                    revalidate()
                }
                return
            }
            val score = current.score()
            val done = current.finishedCount()
            val total = maxOf(current.expected, current.mutants.size)
            val running = current.isRunning
            val digest = "$done/$total|$running|${score.counts}|${current.stopRequested}|${current.exitCode}"
            if (!running && digest == painted && isVisible) return
            painted = digest

            indeterminate = total <= 0
            fraction = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
            escaped = score.escaped
            verdict = when {
                running -> null
                current.stopRequested -> TestoIcons.Status.FAILURE_CANCELLED
                escaped > 0 || (current.exitCode != 0 && current.mutants.isEmpty()) -> TestoIcons.Status.FAILURE
                else -> TestoIcons.Status.SUCCESS
            }
            label = when {
                running -> TestoBundle.message("infection.widget.running", done.toString(), total.toString())
                score.msi != null -> TestoBundle.message("infection.widget.msi", score.msi.toString())
                else -> TestoBundle.message("infection.widget.done")
            }
            val tip = TestoBundle.message(
                "infection.widget.tooltip",
                done.toString(),
                total.toString(),
                escaped.toString(),
                (score.msi ?: 0).toString(),
            )
            if (tip != toolTipText) toolTipText = tip
            if (running) spin = (spin + SPIN_STEP) % 360

            val wasVisible = isVisible
            isVisible = true
            if (!wasVisible || preferredSize.width != width) revalidate()
            repaint()
        }

        override fun getPreferredSize(): Dimension {
            if (!isVisible) return Dimension(0, 0)
            val metrics = getFontMetrics(font)
            var width = SEPARATOR_GAP + PADDING + RING + GAP + metrics.stringWidth(label) + PADDING
            if (escaped > 0) width += GAP + TestoIcons.Status.FAILED.iconWidth + GAP + metrics.stringWidth(escaped.toString())
            return Dimension(width, maxOf(metrics.height, RING) + JBUI.scale(4))
        }

        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                g2.color = SEPARATOR
                val inset = JBUI.scale(3)
                LinePainter2D.paint(g2, 0.0, inset.toDouble(), 0.0, (height - inset).toDouble())

                var x = SEPARATOR_GAP + PADDING
                val icon = verdict
                if (icon != null) icon.paintIcon(this, g2, x, (height - icon.iconHeight) / 2)
                else paintRing(g2, x)
                x += RING + GAP

                g2.font = font
                g2.color = UIUtil.getLabelForeground()
                val metrics = g2.fontMetrics
                val baseline = (height - metrics.height) / 2 + metrics.ascent
                g2.drawString(label, x, baseline)
                x += metrics.stringWidth(label)

                if (escaped > 0) {
                    x += GAP
                    val failed = TestoIcons.Status.FAILED
                    failed.paintIcon(this, g2, x, (height - failed.iconHeight) / 2)
                    x += failed.iconWidth + GAP
                    g2.drawString(escaped.toString(), x, baseline)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun paintRing(g: Graphics2D, x: Int) {
            val stroke = JBUI.scale(2).toFloat()
            val size = (RING - stroke).toDouble()
            val left = x + stroke / 2.0
            val top = (height - RING) / 2.0 + stroke / 2.0
            g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = RING_TRACK
            g.draw(Arc2D.Double(left, top, size, size, 0.0, 360.0, Arc2D.OPEN))
            g.color = RING_PROGRESS
            val extent = if (indeterminate) -SPIN_ARC else -360.0 * fraction
            val start = if (indeterminate) 90.0 - spin else 90.0
            g.draw(Arc2D.Double(left, top, size, size, start, extent, Arc2D.OPEN))
        }
    }

    companion object {
        private const val TICK_MS = 100
        private const val SPIN_STEP = 12
        private const val SPIN_ARC = 90.0

        private val PADDING get() = JBUI.scale(5)
        private val GAP get() = JBUI.scale(4)
        private val SEPARATOR_GAP get() = JBUI.scale(4)
        private val RING get() = JBUI.scale(16)

        private val SEPARATOR = JBColor.namedColor("Toolbar.separatorColor", JBColor(0xCDCDCD, 0x515151))
        private val RING_TRACK = JBColor.namedColor("ProgressBar.trackColor", JBColor(0xD5D5D5, 0x4E5157))
        private val RING_PROGRESS = JBColor.namedColor("ProgressBar.progressColor", JBColor(0x389FD6, 0x3592C4))
    }
}
