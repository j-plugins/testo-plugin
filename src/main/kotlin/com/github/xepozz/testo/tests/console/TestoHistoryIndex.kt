package com.github.xepozz.testo.tests.console

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.TestStateStorage
import com.intellij.execution.testframework.sm.TestHistoryConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cached set of every test locationUrl present in the project's saved run-history XML files, so the "Show history" lens
 * can be shown only for tests that actually have saved history. A test's last status survives in [TestStateStorage]
 * long after its run XML is pruned from the 10-file history, so checking storage alone would show the lens for tests
 * whose history is gone.
 *
 * The index is rebuilt on a pooled thread whenever the history files' newest timestamp changes (i.e. after a new run is
 * saved), then triggers a daemon restart so the lenses recompute. [contains] never blocks: it returns the last good
 * answer while a rebuild is in flight.
 */
internal object TestoHistoryIndex {
    private val locationUrl = Regex("locationUrl=\"([^\"]*)\"")
    private data class Snapshot(val stamp: Long, val urls: Set<String>)
    private val cache = ConcurrentHashMap<String, Snapshot>()
    private val building = ConcurrentHashMap.newKeySet<String>()

    /** True if some saved run history contains [url] (an exact node locationUrl, or a dataset under that method). */
    fun contains(project: Project, url: String): Boolean {
        val key = project.locationHash
        val files = historyFiles(project)
        val stamp = files.maxOfOrNull { it.lastModified() } ?: 0L
        val snap = cache[key]
        if (snap == null || snap.stamp != stamp) scheduleRebuild(project, key, files, stamp)
        val urls = (if (snap?.stamp == stamp) snap else cache[key])?.urls ?: return false
        return url in urls || urls.any { it.startsWith(url) }
    }

    private fun historyFiles(project: Project): List<File> {
        val root = TestStateStorage.getTestHistoryRoot(project)
        return TestHistoryConfiguration.getInstance(project).files.map { File(root, it) }.filter { it.exists() }
    }

    private fun scheduleRebuild(project: Project, key: String, files: List<File>, stamp: Long) {
        if (!building.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val urls = HashSet<String>()
                files.forEach { f ->
                    runCatching { locationUrl.findAll(f.readText()).forEach { urls.add(it.groupValues[1]) } }
                }
                cache[key] = Snapshot(stamp, urls)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
                }
            } finally {
                building.remove(key)
            }
        }
    }
}
