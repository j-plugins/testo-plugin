package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.runs.runLocationKey
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
 * The set is built synchronously on first lookup (a few small `tests.txt`) and cached until the archive changes, so the
 * first code-vision pass over a freshly opened file already answers, without waiting on a repaint (see CLAUDE.md).
 */
internal object TestoHistoryIndex {
    private data class Snapshot(val generation: Long, val urls: Set<String>)

    private val generation = AtomicLong()
    private val cache = ConcurrentHashMap<String, Snapshot>()

    /** The archive changed: the next lookup rebuilds. Callers repaint open editors via [refreshLens] themselves. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** True if some archived run contains [url] (an exact test location, or a test declared under it). */
    fun contains(project: Project, url: String): Boolean {
        val needle = runLocationKey(url)
        val urls = snapshot(project).urls
        // `startsWith("$needle::")`, not `startsWith(needle)`: `…::testPay` must not answer for `…::testPayment`.
        return needle in urls || urls.any { it.startsWith("$needle::") }
    }

    private fun snapshot(project: Project): Snapshot {
        val key = project.locationHash
        val current = generation.get()
        cache[key]?.let { if (it.generation == current) return it }
        val store = TestoRunStore.getInstance(project)
        val urls = HashSet<String>()
        store.listRuns().forEach { (dir, _) -> store.readLocations(dir).forEach { urls.add(runLocationKey(it)) } }
        return Snapshot(current, urls).also { cache[key] = it }
    }

    /**
     * Recompute the "Show history" lenses now: a test run touches no PHP source, so the code-vision pass self-skips
     * unless each editor's PSI modification stamp is cleared first (the platform's own recipe) — then the daemon
     * restart re-runs getHint.
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
