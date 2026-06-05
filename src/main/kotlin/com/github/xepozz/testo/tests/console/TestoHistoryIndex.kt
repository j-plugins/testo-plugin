package com.github.xepozz.testo.tests.console

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.codeVision.ModificationStampUtil
import com.intellij.execution.TestStateStorage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
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

    // List the history directory directly rather than TestHistoryConfiguration.files: a just-saved run's file lands on
    // disk before it is registered there, and we want the lens to appear as soon as the run is written.
    private fun historyFiles(project: Project): List<File> =
        TestStateStorage.getTestHistoryRoot(project).listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.toList()
            ?: emptyList()

    private fun scheduleRebuild(project: Project, key: String, files: List<File>, stamp: Long) {
        if (!building.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val urls = HashSet<String>()
                files.forEach { f ->
                    runCatching { locationUrl.findAll(f.readText()).forEach { urls.add(it.groupValues[1]) } }
                }
                cache[key] = Snapshot(stamp, urls)
                refreshLens(project)
            } finally {
                building.remove(key)
            }
        }
    }

    /**
     * Recompute the "Show history" lenses now. Code vision is gated by a PSI modification stamp: the daemon's code-vision
     * pass self-skips when the file's stamp is unchanged, and a test run never touches the PHP source — so neither
     * DaemonCodeAnalyzer.restart() nor CodeVisionHost.invalidateProvider re-runs getHint (the lens only refreshed on a
     * full IDE restart). The platform's own recipe (CodeVisionHost.subscribeCVSettingsChanged) is to clear that stamp on
     * each editor and then restart the daemon, which forces the pass to recompute getHint and repopulate the cache.
     */
    fun refreshLens(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            // ModificationStampUtil is internal platform API; if it ever moves, degrade to "refreshes on next edit"
            // rather than crashing — the restart below still runs.
            runCatching {
                EditorFactory.getInstance().allEditors
                    .filter { it.project == project }
                    .forEach { ModificationStampUtil.clearModificationStamp(it) }
            }
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
