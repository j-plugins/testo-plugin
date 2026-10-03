package com.github.xepozz.testo

import com.github.xepozz.testo.php.TestoPhp
import com.intellij.openapi.project.Project

object TestoUtil {
    fun isEnabled(project: Project): Boolean = TestoPhp.getInstance().isTestoConfigured(project)
}
