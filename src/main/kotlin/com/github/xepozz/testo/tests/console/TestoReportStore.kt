package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.coverage.format.CoverageFormat
import java.nio.file.Path

/**
 * A report Testo generated during the run, announced with `##teamcity[testoReport …]`.
 *
 * Non-standard by design: the platform knows nothing of it, so the converter consumes the message itself and never
 * forwards it (see [TestoOutputToGeneralEventsConverter]).
 */
data class TestoReportRef(
    val format: String,
    /** Absolute inside the *execution* environment — under a remote interpreter or a container, not a host path. */
    val path: String,
    /** The same file relative to the working directory, when it sits inside it. The way back from a mapped path. */
    val relativePath: String?,
    val name: String?,
    val schemaVersion: String?,
) {
    /** Whether the button can show this report as a page; the rest (data documents, coverage) is kept, not offered. */
    val isViewable: Boolean get() = VIEWABLE_FORMATS.any { format.equals(it, ignoreCase = true) }

    /** A coverage report — clover / cobertura / coverage-xml — that the "Show coverage" button can apply without a rerun. */
    val coverageFormat: CoverageFormat? get() = CoverageFormat.fromId(format)

    val isCoverage: Boolean get() = coverageFormat != null

    companion object {
        const val FORMAT_HTML: String = "html"

        private val VIEWABLE_FORMATS = setOf(FORMAT_HTML)

        private const val MESSAGE_NAME = "testoReport"

        /**
         * The message read straight off raw output, or `null` when it holds none. The platform parses a line only
         * when it *starts* with `##teamcity[`, so one behind a colour escape reaches the console as plain text —
         * hence the scan anywhere in the line; the store dedups by path when both routes deliver.
         */
        fun fromServiceMessageLine(line: String): TestoReportRef? {
            val start = line.indexOf("##teamcity[$MESSAGE_NAME")
            if (start < 0) return null
            val body = line.substring(start + "##teamcity[$MESSAGE_NAME".length)
            // The name has to end here, or `testoReportSomethingElse` would be read as ours.
            if (body.isNotEmpty() && !body[0].isWhitespace() && body[0] != ']') return null
            return fromAttributes(parseServiceMessageAttributes(body))
        }

        /** `null` when the message carries no `path`. */
        fun fromAttributes(attributes: Map<String, String>): TestoReportRef? {
            val path = attributes["path"]?.takeIf { it.isNotBlank() } ?: return null
            return TestoReportRef(
                format = attributes["format"]?.takeIf { it.isNotBlank() } ?: FORMAT_HTML,
                path = path,
                relativePath = attributes["relativePath"]?.takeIf { it.isNotBlank() },
                name = attributes["name"]?.takeIf { it.isNotBlank() },
                schemaVersion = attributes["schemaVersion"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * The reports of the current run, in announcement order. Written by the converter off the process's output thread and
 * read by the toolbar on the EDT, hence the locks; keyed by path, so a re-announced report replaces its entry.
 */
class TestoReportStore {
    private val reports = LinkedHashMap<String, TestoReportRef>()

    // This run's deferred opens: (TestoReportAutoOpen.keyOf, way) pairs, each an independent flag.
    private val autoOpenThisRun = HashSet<Pair<String, ReportOpenWay>>()

    // Reports clicked back off for this run alone — one flag over every way; the standing choices stay checked.
    private val autoOpenMuted = HashSet<String>()

    // Coverage reports unchecked in the grouped Coverage button's dropdown. Checked is the default, so only the
    // exceptions are kept; they survive reruns of the same console — the choice is about the report, not the run.
    private val coverageUnchecked = HashSet<String>()

    /**
     * Whether the process that writes these reports has exited. Nothing is looked for on disk before it has: a report
     * is announced when Testo *starts* writing it, over the same path every run, so an earlier check finds the
     * previous run's file.
     */
    @Volatile
    var runFinished: Boolean = false
        private set

    /**
     * When the current run began, floored to a whole second — a filesystem keeping mtime by the second would
     * otherwise date a report written moments after the start before it. A report older than this is the previous
     * run's, left in place by a run stopped before its reporter ran.
     */
    @Volatile
    var runStartedAt: Long = 0
        private set

    /** A replay pins the clock to the original run, so its captured report copies pass the mtime-vs-start gate. */
    @Volatile
    var startedAtOverride: Long? = null

    fun noteRunStarted(now: Long = System.currentTimeMillis()) {
        runFinished = false
        val at = startedAtOverride ?: now
        runStartedAt = at - at % 1000
        // Arms and mutes belong to the run they were clicked in.
        synchronized(autoOpenThisRun) {
            autoOpenThisRun.clear()
            autoOpenMuted.clear()
        }
    }

    fun noteRunFinished() {
        runFinished = true
    }

    fun note(ref: TestoReportRef) {
        synchronized(reports) { reports[ref.path] = ref }
    }

    /** Arming lifts the report's mute — it is the newer word. */
    fun armAutoOpen(key: String, way: ReportOpenWay, armed: Boolean) {
        synchronized(autoOpenThisRun) {
            if (armed) {
                autoOpenThisRun.add(key to way)
                autoOpenMuted.remove(key)
            } else {
                autoOpenThisRun.remove(key to way)
            }
        }
    }

    fun isAutoOpenArmed(key: String, way: ReportOpenWay): Boolean =
        synchronized(autoOpenThisRun) { key to way in autoOpenThisRun }

    /** Muting also takes this run's own arms back. */
    fun muteAutoOpen(key: String, muted: Boolean) {
        synchronized(autoOpenThisRun) {
            if (muted) {
                autoOpenMuted.add(key)
                autoOpenThisRun.removeAll { it.first == key }
            } else {
                autoOpenMuted.remove(key)
            }
        }
    }

    fun isAutoOpenMuted(key: String): Boolean = synchronized(autoOpenThisRun) { key in autoOpenMuted }

    fun isCoverageChecked(path: String): Boolean = synchronized(coverageUnchecked) { path !in coverageUnchecked }

    fun setCoverageChecked(path: String, checked: Boolean) {
        synchronized(coverageUnchecked) { if (checked) coverageUnchecked.remove(path) else coverageUnchecked.add(path) }
    }

    fun clear() {
        runFinished = false
        runStartedAt = 0
        synchronized(reports) { reports.clear() }
        synchronized(autoOpenThisRun) {
            autoOpenThisRun.clear()
            autoOpenMuted.clear()
        }
        synchronized(coverageUnchecked) { coverageUnchecked.clear() }
    }

    fun all(): List<TestoReportRef> = synchronized(reports) { reports.values.toList() }

    fun viewable(): List<TestoReportRef> = all().filter { it.isViewable }

    fun coverage(): List<TestoReportRef> = all().filter { it.isCoverage }

    fun primary(): TestoReportRef? = viewable().lastOrNull()
}

/**
 * `key='value'` pairs up to the closing `]`, with TeamCity's escaping undone. Hand-rolled because this runs on text
 * the platform has already declined to parse; anything malformed is skipped rather than thrown over.
 */
internal fun parseServiceMessageAttributes(body: String): Map<String, String> {
    val attributes = LinkedHashMap<String, String>()
    var i = 0
    while (i < body.length) {
        when {
            body[i] == ']' -> return attributes
            body[i].isWhitespace() -> i++
            else -> {
                val eq = body.indexOf('=', i)
                if (eq < 0 || eq + 1 >= body.length || body[eq + 1] != '\'') return attributes
                val key = body.substring(i, eq).trim()
                val value = StringBuilder()
                var j = eq + 2
                while (j < body.length && body[j] != '\'') {
                    if (body[j] == '|' && j + 1 < body.length) {
                        value.append(unescapeServiceMessageChar(body[j + 1]))
                        j += 2
                    } else {
                        value.append(body[j])
                        j++
                    }
                }
                if (j >= body.length) return attributes
                if (key.isNotEmpty()) attributes[key] = value.toString()
                i = j + 1
            }
        }
    }
    return attributes
}

// The letters TeamCity gives a meaning to; every other escape (`|'`, `||`, `|[`, `|]`) stands for the character itself.
private fun unescapeServiceMessageChar(escaped: Char): String = when (escaped) {
    'n' -> "\n"
    'r' -> "\r"
    'x' -> "\u0085"
    'l' -> "\u2028"
    'p' -> "\u2029"
    else -> escaped.toString()
}

/**
 * Where the announced report might sit on this machine, best guess first: the deployment mapper, the raw path, then
 * `relativePath` under the project root — the one that survives when the run's filesystem shares nothing with the host.
 */
fun reportPathCandidates(
    ref: TestoReportRef,
    projectBasePath: String?,
    mapToLocal: (String) -> String?,
): List<String> = buildList {
    mapToLocal(ref.path)?.takeIf { it.isNotBlank() }?.let { add(it) }
    add(ref.path)
    if (projectBasePath != null && ref.relativePath != null) {
        add(Path.of(projectBasePath, ref.relativePath).toString())
    }
}.distinct()
