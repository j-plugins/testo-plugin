package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.runs.TestoRunStore
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.codeVision.ModificationStampUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cached set of every test location the project's archived runs hold, so the "Show history" lens is shown only for
 * tests some archive can actually replay. The source is the run archive ([TestoRunStore]) — the platform's own history
 * XMLs are not consulted: a lens click replays our archive, so the two would disagree the moment either side rotates.
 *
 * The index is rebuilt on a pooled thread and never blocks: [contains] answers from the last snapshot while a rebuild
 * is in flight. It is invalidated by exactly one event — an archived run becoming complete
 * ([com.github.xepozz.testo.runs.TestoRunArchiver], which also prunes) — so the lookup itself touches no filesystem.
 */
internal object TestoHistoryIndex {
    private data class Snapshot(val generation: Long, val urls: Set<String>)

    private val generation = AtomicLong()
    private val cache = ConcurrentHashMap<String, Snapshot>()
    private val building = ConcurrentHashMap.newKeySet<String>()

    /** The archive changed: rebuild on the next lookup. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** True if some archived run contains [url] (an exact test location, or a test declared under it). */
    fun contains(project: Project, url: String): Boolean {
        val key = project.locationHash
        val current = generation.get()
        val snapshot = cache[key]
        if (snapshot == null || snapshot.generation != current) scheduleRebuild(project, key, current)
        val urls = snapshot?.urls ?: cache[key]?.urls ?: return false
        return url in urls || urls.any { it.startsWith(url) }
    }

    private fun scheduleRebuild(project: Project, key: String, generation: Long) {
        if (!building.add(key)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (project.isDisposed) return@executeOnPooledThread
                val store = TestoRunStore.getInstance(project)
                val urls = HashSet<String>()
                store.listRuns().forEach { (dir, _) -> urls.addAll(store.readLocations(dir)) }
                // Only restart the daemon when the lenses would actually change: a rebuild also runs on the very first
                // lookup, and restarting then interrupts an in-flight highlighting pass for nothing.
                val previous = cache.put(key, Snapshot(generation, urls))?.urls ?: emptySet()
                if (previous != urls) refreshLens(project)
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
