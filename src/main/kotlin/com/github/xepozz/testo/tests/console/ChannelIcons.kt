package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoIcons
import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Maps a channel's `icon='...'` service-message hint — or, failing that, its own name — to the platform icon shown
 * on its tab. Shared by the tab builder ([TestoChannelsUi]) and the preview action (`Tools | Testo | Channel Icons`).
 * Keys are matched lower-cased; the icon is tinted with the channel colour at display time.
 */
object ChannelIcons {

    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /**
     * Resolves an `icon=` hint OR a raw channel name to an icon: the lower-cased value as a whole first, then each
     * alphanumeric token in it (so `assert_history` matches `assert`, `stdout` matches `stdout`). Null when nothing fits.
     */
    fun match(hint: String): Icon? {
        val key = hint.lowercase()
        MAP[key]?.let { return it }
        return key.split(NON_ALNUM).firstNotNullOfOrNull { token -> token.takeIf { it.isNotEmpty() }?.let(MAP::get) }
    }

    val MAP: Map<String, Icon> = linkedMapOf(
        // the one true channel — Testo's khinkali
        "testo" to TestoIcons.TESTO,
        "khinkali" to TestoIcons.TESTO,
        // assertions / expectations
        "assert" to AllIcons.General.InspectionsOK,
        "expect" to AllIcons.General.InspectionsOK,
        // benchmarks / performance / timing
        "bench" to AllIcons.Actions.Profile,
        "benchmark" to AllIcons.Actions.Profile,
        "profile" to AllIcons.Actions.Profile,
        "cpu" to AllIcons.Actions.Profile,
        "time" to AllIcons.Vcs.History,
        "memory" to AllIcons.Actions.Dump,
        // database
        "sql" to AllIcons.Nodes.DataTables,
        "db" to AllIcons.Nodes.DataTables,
        "database" to AllIcons.Nodes.DataTables,
        "query" to AllIcons.Nodes.DataTables,
        // logging / output streams
        "log" to AllIcons.Debugger.Console,
        "logs" to AllIcons.Debugger.Console,
        "trace" to AllIcons.Debugger.Console,
        "console" to AllIcons.Debugger.Console,
        "output" to AllIcons.Debugger.Console,
        "stderr" to AllIcons.General.Error,
        "dump" to AllIcons.Actions.Dump,
        // filesystem
        "file" to AllIcons.FileTypes.Text,
        "fs" to AllIcons.FileTypes.Text,
        "io" to AllIcons.FileTypes.Text,
        // network / http
        "network" to AllIcons.General.Web,
        "net" to AllIcons.General.Web,
        "http" to AllIcons.General.Web,
        "request" to AllIcons.General.Web,
        // control-flow channels Testo already emits
        "repeat" to AllIcons.Actions.Refresh,
        "retry" to AllIcons.Actions.Restart,
        "stdout" to AllIcons.Debugger.Console,
        // coverage / quality / diagnostics
        "coverage" to AllIcons.General.InspectionsEye,
        "deprecation" to AllIcons.General.BalloonWarning,
        "exception" to AllIcons.General.Error,
        "security" to AllIcons.General.InspectionsEye,
        // severities
        "info" to AllIcons.General.Information,
        "warning" to AllIcons.General.Warning,
        "warn" to AllIcons.General.Warning,
        "error" to AllIcons.General.Error,
        "debug" to AllIcons.Toolwindows.ToolWindowDebugger,
        // misc events
        "event" to AllIcons.Nodes.Plugin,
        "mail" to AllIcons.Actions.Preview,
        "queue" to AllIcons.Actions.ListFiles,
        "cache" to AllIcons.Actions.Refresh,
    )
}
