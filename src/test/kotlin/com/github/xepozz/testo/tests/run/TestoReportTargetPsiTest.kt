package com.github.xepozz.testo.tests.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.config.interpreters.PhpInterpreter

/** [PhpInterpreter] resolves its SDK type from an extension point, so it needs the platform. */
class TestoReportTargetPsiTest : BasePlatformTestCase() {

    fun testLocalInterpreterWritesAtTheLocalPath() {
        val target = TestoReportTarget.resolve(project, PhpInterpreter(), "/ide/report.html")
        assertEquals("/ide/report.html", target.path)
        assertTrue(target.isReachable)
    }
}
