package com.github.xepozz.testo.tests.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages

/**
 * Debug-panel action: "Add File to Skipped Paths". When invoked from a stack frame or editor during a debug session,
 * opens the PHP | Debug | Skipped Paths settings page so the user can add the current file (e.g. Composer autoload
 * scripts) to the skip list. The file path is shown in a confirmation dialog first.
 */
class TestoAddSkippedPathAction : AnAction(
    "Add File to Skipped Paths",
    "Open PHP Debug Skipped Paths settings to exclude this file from stepping",
    null,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val path = file.path

        val result = Messages.showOkCancelDialog(
            project,
            "Add the following path to PHP Debug Skipped Paths?\n\n$path",
            "Add to Skipped Paths",
            "Open Settings",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (result == Messages.OK) {
            // Open the PHP Debug settings page where the user can add the path to Skipped Paths.
            // The configurable ID for PHP Debug is "reference.webide.settings.dialog.xdebug".
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "PHP")
        }
    }
}
