package com.github.xepozz.testo.phpstorm.tests.run

import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.jetbrains.php.phpunit.coverage.PhpCoverageResultManager
import com.jetbrains.php.run.PhpRunConfiguration
import junit.framework.TestCase
import java.lang.reflect.Proxy

class TestoReportTargetTest : TestCase() {

    private val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, _, _ -> null } as Project

    /** Mirrors the PHP plugin's remote managers: the copy is a protected method declared on an abstract parent. */
    private abstract class RemoteLikeManager : PhpCoverageResultManager() {
        override fun processCoverageFile(localPath: String): String = "/remote/$localPath"
        override fun attachToProcess(project: Project, configuration: PhpRunConfiguration<*>,handler: ProcessHandler, settings: RunnerSettings?) = Unit
        protected abstract fun copyFromRemote(project: Project)
    }

    private class CountingManager(private val fail: Boolean = false) : RemoteLikeManager() {
        var copies = 0
        override fun copyFromRemote(project: Project) {
            copies++
            if (fail) error("transfer failed")
        }
    }

    fun testCopyToLocalInvokesTheManagersProtectedCopy() {
        val manager = CountingManager()
        TestoReportTarget("/ide/report.html", "/remote/report.html", manager, true).copyToLocal(project)
        assertEquals(1, manager.copies)
    }

    fun testFailedCopyStaysInside() {
        val manager = CountingManager(fail = true)
        TestoReportTarget("/ide/report.html", "/remote/report.html", manager, true).copyToLocal(project)
        assertEquals(1, manager.copies)
    }

    fun testLocalPathOfAnnouncedTarget() {
        val targets = listOf(
            TestoReportTarget("C:\\ide\\report.html", "/mnt/c/ide/report.html", null, true),
            TestoReportTarget("C:\\ide\\junit.xml", "/mnt/c/ide/junit.xml", null, true),
        )
        assertEquals("C:\\ide\\junit.xml", TestoReportTarget.localPathOf("/mnt/c/ide/junit.xml", targets))
        assertNull(TestoReportTarget.localPathOf("/srv/app/build/report.html", targets))
    }

    fun testLocalPathOfFileInsideADirectoryTarget() {
        val targets = listOf(TestoReportTarget("C:\\ide\\run-coverage-xml", "/opt/phpstorm-coverage/run-coverage-xml", null, true))
        assertEquals(
            "C:\\ide\\run-coverage-xml/index.xml",
            TestoReportTarget.localPathOf("/opt/phpstorm-coverage/run-coverage-xml/index.xml", targets),
        )
        assertNull(TestoReportTarget.localPathOf("/opt/phpstorm-coverage/run-coverage-xml-old/index.xml", targets))
    }
}
