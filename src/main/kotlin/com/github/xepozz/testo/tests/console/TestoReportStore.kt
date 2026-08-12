package com.github.xepozz.testo.tests.console

import java.nio.file.Path

/**
 * A report Testo generated during the run, announced with `##teamcity[testoReport …]`.
 *
 * Non-standard by design: the platform knows nothing of it, so the converter consumes the message itself and never
 * forwards it (see [TestoOutputToGeneralEventsConverter]).
 */
data class TestoReportRef(
    /** `html` today; kept so a future format can be told apart rather than guessed from the extension. */
    val format: String,
    /** Absolute inside the *execution* environment — under a remote interpreter or a container, not a host path. */
    val path: String,
    /** The same file relative to the working directory, when it sits inside it. The way back from a mapped path. */
    val relativePath: String?,
    val name: String?,
    val schemaVersion: String?,
) {
    /**
     * Whether this is a report the button can show as a page.
     *
     * Testo announces every report it writes, and not all are pages — a data document for external tooling has nothing
     * to open in a browser. Such formats are kept for handling of their own, not offered here.
     */
    val isViewable: Boolean get() = VIEWABLE_FORMATS.any { format.equals(it, ignoreCase = true) }

    companion object {
        const val FORMAT_HTML: String = "html"

        private val VIEWABLE_FORMATS = setOf(FORMAT_HTML)

        private const val MESSAGE_NAME = "testoReport"

        /**
         * The message read straight off raw output, or `null` when it holds none.
         *
         * The platform parses a line only when it *starts* with `##teamcity[`, so anything in front of it (a colour
         * escape, output not terminated by a newline) leaves the announcement to reach the console as plain text. Hence
         * the scan for the message anywhere in the text; the store deduplicates by path when both routes deliver.
         */
        fun fromServiceMessageLine(line: String): TestoReportRef? {
            val start = line.indexOf("##teamcity[$MESSAGE_NAME")
            if (start < 0) return null
            val body = line.substring(start + "##teamcity[$MESSAGE_NAME".length)
            // The name has to end here, or `testoReportSomethingElse` would be read as ours.
            if (body.isNotEmpty() && !body[0].isWhitespace() && body[0] != ']') return null
            return fromAttributes(parseServiceMessageAttributes(body))
        }

        /** `null` when the message carries no `path`, which is the one attribute nothing can be done without. */
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
 * The reports of the current run, in announcement order.
 *
 * Written by the converter off the process's output thread and read by the toolbar button on the EDT, hence the lock.
 * Keyed by path: a re-announced report replaces its earlier entry instead of stacking up.
 */
class TestoReportStore {
    private val reports = LinkedHashMap<String, TestoReportRef>()

    /**
     * Whether the process that writes these reports has exited.
     *
     * Nothing is looked for on disk before it has: a report is announced when Testo *starts* writing it, and it is
     * written to the same path every run — so a file check while the run is going finds the previous run's report and
     * offers it as this one's. Volatile: set from the process's thread, read by the toolbar on the EDT.
     */
    @Volatile
    var runFinished: Boolean = false
        private set

    fun noteRunStarted() {
        runFinished = false
    }

    fun noteRunFinished() {
        runFinished = true
    }

    fun note(ref: TestoReportRef) {
        synchronized(reports) { reports[ref.path] = ref }
    }

    fun clear() {
        runFinished = false
        synchronized(reports) { reports.clear() }
    }

    fun all(): List<TestoReportRef> = synchronized(reports) { reports.values.toList() }

    /** Every report the button can show, in announcement order. */
    fun viewable(): List<TestoReportRef> = all().filter { it.isViewable }

    /** What the button opens: the last viewable report announced, since that is the one this run wrote last. */
    fun primary(): TestoReportRef? = viewable().lastOrNull()
}

/**
 * `key='value'` pairs up to the closing `]`, with TeamCity's escaping undone.
 *
 * A hand-rolled reader rather than the platform's parser, because this runs on text the platform has already declined
 * to parse. Values are single-quoted and `|` escapes; anything malformed is skipped rather than thrown over.
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
 * Where the announced report might sit on this machine, best guess first.
 *
 * The mapper leads: it is the only candidate that knows about deployment, and for a local interpreter it answers with
 * the path itself anyway. The raw path follows for the plain local run, and the project-relative form is the last
 * resort — it is what survives when the execution environment's filesystem has nothing in common with the host's.
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
