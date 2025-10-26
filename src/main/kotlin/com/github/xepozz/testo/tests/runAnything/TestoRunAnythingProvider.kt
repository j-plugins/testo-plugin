package com.github.xepozz.testo.tests.runAnything

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.tests.actions.TestoRunCommandAction
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.runAnything.activity.RunAnythingAnActionProvider
import com.intellij.openapi.actionSystem.DataContext

class TestoRunAnythingProvider : RunAnythingAnActionProvider<TestoRunCommandAction>() {
    override fun getCommand(value: TestoRunCommandAction) =
        TestoBundle.message("action.run.target.command", value.commandName)

    override fun getHelpCommandPlaceholder() = "testo <command>"

    override fun getCompletionGroupTitle() = "Testo"

    override fun getHelpCommand() = "icons/testo"

    override fun getHelpGroupTitle() = "PHP"

    override fun getHelpIcon() = TestoIcons.TESTO

    override fun getIcon(value: TestoRunCommandAction) = AllIcons.Actions.Execute

    override fun getValues(dataContext: DataContext, pattern: String) = listOf(TestoRunCommandAction("run"))
}