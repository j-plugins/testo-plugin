package com.github.xepozz.testo.tests.console

import com.intellij.ide.util.PropertiesComponent

/**
 * Display-time filter for per-message log levels (PSR-style: error/warning/info/debug/…). The [ChannelOutputStore]
 * always keeps every chunk; this only decides what the channel UI renders. Levels actually seen in the current run are
 * recorded in [seenLevels] so the toolbar menu can list exactly what occurred, and the set of [hidden] levels is
 * persisted application-wide so a choice survives test reruns and IDE restarts.
 *
 * Chunks without a level, plus the synthetic `stderr`/`stdout` streams (plain output and failed-test details), are not
 * log messages and are always visible regardless of the filter.
 */
class LogLevelFilter {
    private val lock = Any()
    private val seen = LinkedHashSet<String>()

    // Read on the test-reader thread (isVisible) and the EDT (toggles); swapped atomically so reads need no lock.
    @Volatile
    private var hidden: Set<String> = loadHidden()

    /** Set by the channel UI to rebuild its tabs when the filter changes; cleared on dispose. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun isVisible(level: String?): Boolean {
        if (level == null) return true
        val normalized = level.lowercase()
        if (normalized == STDERR || normalized == STDOUT) return true
        return normalized !in hidden
    }

    /** Records a real log level; returns true if it had not been seen before. No-op for null/stderr/stdout. */
    fun noteSeen(level: String?): Boolean {
        if (level == null) return false
        val normalized = level.lowercase()
        if (normalized == STDERR || normalized == STDOUT) return false
        return synchronized(lock) { seen.add(normalized) }
    }

    fun seenLevels(): List<String> = synchronized(lock) { seen.toList() }

    fun isHidden(level: String): Boolean = level.lowercase() in hidden

    fun isAllEnabled(): Boolean = hidden.isEmpty()

    fun setHidden(level: String, hide: Boolean) {
        val normalized = level.lowercase()
        hidden = if (hide) hidden + normalized else hidden - normalized
        persist()
    }

    fun enableAll() {
        hidden = emptySet()
        persist()
    }

    // Disables only the levels seen so far; a level first seen later is shown by default (you don't want a never-before
    // seen error to be silently swallowed by an earlier "hide all").
    fun disableAll() {
        hidden = seenLevels().toSet()
        persist()
    }

    fun fireChange() {
        onChange?.invoke()
    }

    private fun persist() {
        PropertiesComponent.getInstance().setValue(KEY, hidden.joinToString(","), "")
    }

    private fun loadHidden(): Set<String> =
        PropertiesComponent.getInstance().getValue(KEY, "")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        private const val KEY = "testo.console.hiddenLogLevels"
        private const val STDERR = "stderr"
        private const val STDOUT = "stdout"
    }
}
