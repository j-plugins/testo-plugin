package com.github.xepozz.testo.openide.tests.run

import com.github.xepozz.testo.TestoBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** Tells once per environment and configuration in an IDE session that a run went without its HTML and JUnit reports. */
@Service(Service.Level.PROJECT)
internal class TestoUnreachableReportsNotice(private val project: Project) {
    private val told = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    private fun shouldTell(environment: String, configuration: String): Boolean = told.add(environment to configuration)

    fun tell(environment: String, configuration: String, reason: String) {
        if (!shouldTell(environment, configuration)) return
        ApplicationManager.getApplication().invokeLater({
            NotificationGroupManager.getInstance().getNotificationGroup("Testo")
                ?.createNotification(
                    TestoBundle.message("notification.reports.unreachable.title"),
                    TestoBundle.message("notification.reports.unreachable", environment, configuration, reason),
                    NotificationType.WARNING,
                )
                ?.notify(project)
        }, project.disposed)
    }

    companion object {
        fun getInstance(project: Project): TestoUnreachableReportsNotice = project.getService(TestoUnreachableReportsNotice::class.java)
    }
}
