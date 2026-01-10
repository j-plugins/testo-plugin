package com.github.xepozz.testo.tests.actions

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.tests.TestoTestCreateInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.php.phpunit.codeGeneration.PhpNewTestAction
import com.jetbrains.php.testFramework.PhpTestCreateInfo

class TestoNewTestFromClassAction : PhpNewTestAction(
    TestoBundle.messagePointer("actions.new.test.action.name", arrayOfNulls<Any>(0)),
    TestoBundle.messagePointer("actions.new.test.action.description", arrayOfNulls<Any>(0)),
    TestoIcons.TESTO
) {
    override fun getDefaultTestCreateInfo(
        project: Project,
        locationContext: VirtualFile?
    ): PhpTestCreateInfo {
        return TestoTestCreateInfo.INSTANCE
    }
}
