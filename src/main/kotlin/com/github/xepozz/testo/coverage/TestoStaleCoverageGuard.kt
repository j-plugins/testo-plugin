package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.InvalidPathException

/**
 * A stale coverage suite in `workspace.xml` — a report whose absolute file no longer exists — makes the platform's
 * `CoverageDataSuitesManager` throw while loading: on Windows `Path.of(systemPath, absolutePath)` hits the second drive
 * letter and raises [InvalidPathException], and the whole coverage subsystem fails to init. It fires before any plugin
 * code, on the first coverage use, and the offending suite is often another plugin's (e.g. PhpUnit's `PhpCoverage`),
 * so we cannot stop the load. We instead recognise the failure when it surfaces on a Testo run and offer to drop the
 * persisted coverage component — which the platform re-reads only on restart.
 */
object TestoStaleCoverageGuard {
    private const val COVERAGE_COMPONENT = "com.intellij.coverage.CoverageDataManagerImpl"
    private val LOG = Logger.getInstance(TestoStaleCoverageGuard::class.java)

    /** The signatures of the stale-suite load failure: the `InvalidPathException` itself, or the wrapping component error. */
    fun isStaleCoverageFailure(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is InvalidPathException) return true
            val message = cause.message.orEmpty()
            if ("CoverageDataSuitesManager" in message || COVERAGE_COMPONENT in message) return true
            cause = cause.cause
        }
        return false
    }

    fun notifyStaleCoverage(project: Project) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Testo")
            ?.createNotification(
                TestoBundle.message("testo.coverage.stale.title"),
                TestoBundle.message("testo.coverage.stale.text"),
                NotificationType.WARNING,
            ) ?: return
        notification.addAction(NotificationAction.createSimple(TestoBundle.message("testo.coverage.stale.cleanup")) {
            if (removeCoverageComponent(project)) {
                notification.expire()
                ApplicationManager.getApplication().restart()
            }
        })
        notification.notify(project)
    }

    /**
     * Drops the whole `CoverageDataManagerImpl` component from `workspace.xml` — it only remembers past coverage-result
     * files, so nothing of value is lost. The edit takes effect on the next start: the failed component is not written
     * back on exit, so restarting re-reads the cleaned file.
     */
    private fun removeCoverageComponent(project: Project): Boolean {
        val workspace = project.workspaceFile ?: return false
        return try {
            val root = workspace.inputStream.use { JDOMUtil.load(it) }
            val component = root.getChildren("component")
                .firstOrNull { it.getAttributeValue("name") == COVERAGE_COMPONENT }
                ?: return false
            root.removeContent(component)
            val text = JDOMUtil.write(root)
            ApplicationManager.getApplication().runWriteAction { VfsUtil.saveText(workspace, text) }
            true
        } catch (e: Exception) {
            LOG.warn("Failed to clean stale coverage data from ${workspace.path}", e)
            false
        }
    }
}
