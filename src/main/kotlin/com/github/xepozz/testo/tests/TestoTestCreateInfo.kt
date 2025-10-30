package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoIcons
import com.intellij.openapi.project.Project
import com.jetbrains.php.lang.PhpCodeUtil
import com.jetbrains.php.testFramework.PhpUnitAbstractTestCreateInfo

class TestoTestCreateInfo : PhpUnitAbstractTestCreateInfo() {
    override fun getIcon() = TestoIcons.TESTO

    override fun getName() = "Testo"

    override fun getTemplateName() = "Testo Test"

    override fun getTestMethodText(project: Project, classFqn: String, methodName: String): String {
        return PhpCodeUtil.getCodeTemplate(
            "Testo Test Method",
            this.getDefaultProperties(methodName, classFqn),
            project,
        )
    }
    companion object Companion {
        val INSTANCE = TestoTestCreateInfo()
    }
}
