package com.github.xepozz.testo.runs

import com.github.xepozz.testo.tests.console.TestoRunTimings
import com.google.gson.annotations.SerializedName

/**
 * One report of an archived run, as announced by `##teamcity[testoReport …]` plus where its captured copy sits.
 * [stored] is run-dir-relative (`reports/cobertura.xml`, or `reports/coverage-xml` — a directory); null when the file
 * was not captured (a non-coverage report, or one that never appeared on disk).
 */
data class StoredReport(
    val format: String = "",
    val name: String? = null,
    val path: String = "",
    val relativePath: String? = null,
    val stored: String? = null,
)

/** What retention is allowed to do with an archived run. Chosen per run, from the tab's *Replay* group. */
enum class RunRetention {
    /** The default: kept until the newest-N rotation drops it. */
    AUTO,

    /** Dropped at the next prune, and hidden from the history at once. */
    DISCARD,

    /** Never rotated out, and left alone by "clear history": the user locked this one. */
    @SerializedName(value = "LOCKED", alternate = ["PINNED"])
    LOCKED,
}

/**
 * `run.json` — the metadata of one archived run. Written once at run end; its presence is what marks a run directory
 * as complete (a directory without one is a run that crashed mid-flight and is swept by retention).
 *
 * [executorId] and [statuses] exist for the history chooser alone: it shows what kind of run this was and how it ended
 * without replaying it. A v1 manifest has neither, and renders as a plain run with no tally.
 */
data class TestoRunManifest(
    val v: Int = VERSION,
    val configurationName: String = "",
    /** `Run` / `Debug` / `Coverage` — the executor the run was started with. */
    val executorId: String = "",
    /** The process command line, as the console header printed it. */
    val commandLine: String = "",
    /** The run configuration as XML (`RunConfiguration.writeExternal`), so a replayed tab can rerun the real thing. */
    val configuration: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /**
     * The toolbar clock's own marks. Kept apart from [startedAt]/[finishedAt], which bracket the *archive*: these are
     * what the run summary renders (and breaks into startup / tests / post-processing).
     */
    val timings: TestoRunTimings.Marks = TestoRunTimings.Marks(),
    val retention: RunRetention = RunRetention.AUTO,
    /** [com.github.xepozz.testo.tests.console.TestoTestStatus.wireName] → how many tests ended that way. */
    val statuses: Map<String, Int> = emptyMap(),
    val reports: List<StoredReport> = emptyList(),
) {
    companion object {
        const val VERSION = 3
    }
}
