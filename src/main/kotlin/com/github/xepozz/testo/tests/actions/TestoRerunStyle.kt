package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

enum class TestoRerunStyle { MIRROR_AWARE, SPLIT_BUTTON }

object TestoRerunStyleSettings {
    private const val KEY = "testo.rerun.toolbar.style"

    var style: TestoRerunStyle
        get() = PropertiesComponent.getInstance().getValue(KEY)
            ?.let { runCatching { TestoRerunStyle.valueOf(it) }.getOrNull() }
            ?: TestoRerunStyle.SPLIT_BUTTON
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value.name)
}

open class TestoRerunStyleToggleAction(
    text: String,
    private val style: TestoRerunStyle,
) : ToggleAction(text), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent): Boolean = TestoRerunStyleSettings.style == style
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) TestoRerunStyleSettings.style = style
    }
}

class TestoRerunStyleMirrorAction : TestoRerunStyleToggleAction(
    TestoBundle.message("action.testo.rerunStyle.mirror.text"),
    TestoRerunStyle.MIRROR_AWARE,
)

class TestoRerunStyleSplitAction : TestoRerunStyleToggleAction(
    TestoBundle.message("action.testo.rerunStyle.split.text"),
    TestoRerunStyle.SPLIT_BUTTON,
)
