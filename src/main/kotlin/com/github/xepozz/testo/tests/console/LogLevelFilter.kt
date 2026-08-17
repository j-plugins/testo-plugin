package com.github.xepozz.testo.tests.console

import com.intellij.ide.util.PropertiesComponent

/**
 * Display-time filter for per-message log levels. The [ChannelOutputStore] always keeps every chunk; this only decides
 * what the channel UI renders. It is a single *minimum level*: a message shows when its level is at or above the chosen
 * one (`warning` shows emergency…warning, hides notice/info/debug). The choice is persisted application-wide so it
 * survives reruns and IDE restarts; the default is [DEFAULT] (`info`).
 *
 * Chunks without a level, plus the synthetic `stderr`/`stdout` streams (plain output and failed-test details), are not
 * log messages and are always visible regardless of the filter; so is any level outside the known [LEVELS].
 */
class LogLevelFilter {
    // Read on the test-reader thread (isVisible) and the EDT (menu); assigned atomically so reads need no lock.
    @Volatile
    var minLevel: String = load()
        private set

    /** Set by the channel UI to rebuild its tabs when the filter changes; cleared on dispose. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun isVisible(level: String?): Boolean {
        if (level == null) return true
        val normalized = level.lowercase()
        if (normalized == STDERR || normalized == STDOUT) return true
        val index = LEVELS.indexOf(normalized)
        if (index < 0) return true
        return index <= LEVELS.indexOf(minLevel)
    }

    fun setMinLevel(level: String) {
        val normalized = level.lowercase()
        if (normalized !in LEVELS || normalized == minLevel) return
        minLevel = normalized
        PropertiesComponent.getInstance().setValue(KEY, minLevel, DEFAULT)
    }

    /** The dropdown button's label, e.g. `info +` — the chosen minimum, and everything above it. */
    fun label(): String = "$minLevel +"

    fun fireChange() {
        onChange?.invoke()
    }

    private fun load(): String =
        PropertiesComponent.getInstance().getValue(KEY, DEFAULT).lowercase().takeIf { it in LEVELS } ?: DEFAULT

    companion object {
        // PSR-3 Level enum, most severe first; a chosen level shows itself and everything to its left.
        val LEVELS = listOf("emergency", "alert", "critical", "error", "warning", "notice", "info", "debug")
        const val DEFAULT = "info"
        private const val KEY = "testo.console.minLogLevel"
        private const val STDERR = "stderr"
        private const val STDOUT = "stdout"
    }
}
