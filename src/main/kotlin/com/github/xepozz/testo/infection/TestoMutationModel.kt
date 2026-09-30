package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.AnimatedIcon
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon

/** Infection's `DetectionStatus`, by the value its TeamCity logger prints after `Mutation result:`. */
enum class MutantStatus(val wireName: String, private val labelKey: String, val icon: Icon) {
    KILLED("killed by tests", "infection.status.killed", TestoIcons.Status.PASSED),
    KILLED_BY_SA("killed by SA", "infection.status.killedBySa", TestoIcons.Status.PASSED),
    ESCAPED("escaped", "infection.status.escaped", TestoIcons.Status.FAILED),
    TIMED_OUT("timed out", "infection.status.timedOut", TestoIcons.Status.RISKY),
    ERROR("error", "infection.status.error", TestoIcons.Status.ERROR),
    SYNTAX_ERROR("syntax error", "infection.status.syntaxError", TestoIcons.Status.ERROR),
    NOT_COVERED("not covered", "infection.status.notCovered", TestoIcons.Status.CANCELLED),
    SKIPPED("skipped", "infection.status.skipped", TestoIcons.Status.SKIPPED),
    IGNORED("ignored", "infection.status.ignored", TestoIcons.Status.SKIPPED);

    val label: String get() = TestoBundle.message(labelKey)

    /** Counted as a win for the tests by Infection's MSI. */
    val isDefeated: Boolean get() = this in DEFEATED

    /** Left out of the MSI's denominator altogether. */
    val isExcluded: Boolean get() = this == SKIPPED || this == IGNORED

    companion object {
        private val DEFEATED = setOf(KILLED, KILLED_BY_SA, TIMED_OUT, ERROR, SYNTAX_ERROR)

        fun fromWire(value: String): MutantStatus? = entries.firstOrNull { it.wireName == value.trim() }

        /** The status a `Mutation result: …` line names, anywhere in [text]. */
        fun fromMessage(text: String): MutantStatus? = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(RESULT_PREFIX) }
            ?.let { fromWire(it.removePrefix(RESULT_PREFIX)) }

        private const val RESULT_PREFIX = "Mutation result:"

        val RUNNING_ICON: Icon get() = AnimatedIcon.Default.INSTANCE
        val UNFINISHED_ICON: Icon get() = TestoIcons.Status.ABORTED
    }
}

/** A source file Infection mutates; [path] is as the interpreter sees it. */
class MutatedFile(val nodeId: String, val name: String, val path: String) {
    val mutants: MutableList<Mutant> = CopyOnWriteArrayList()
}

class Mutant(
    val nodeId: String,
    val file: MutatedFile,
    /** `Infection\Mutator\Boolean\FalseValue`. */
    val mutatorClass: String,
    val hash: String,
    /** Offsets of the mutated code in the source file. */
    val start: Int?,
    val end: Int?,
) {
    val mutator: String get() = mutatorClass.substringAfterLast('\\')

    @Volatile
    var status: MutantStatus? = null

    @Volatile
    var finished = false

    /** Only an escaped mutant carries its code: the original and the mutated snippet, with context lines. */
    @Volatile
    var original: String? = null

    @Volatile
    var mutated: String? = null

    /** The file's line, 1-based, that [original] and [mutated] start at; known once the text log is read. */
    @Volatile
    var firstLine: Int? = null

    @Volatile
    var durationMs: Long? = null

    /** What the test run against this mutant printed; read off the text log once Infection is done. */
    @Volatile
    var output: String? = null

    /** The status it had before its latest rerun; null when it was never rerun. */
    @Volatile
    var previousStatus: MutantStatus? = null

    /** Waiting for, or in, a rerun of its own. */
    @Volatile
    var rerunning = false
}

/** Infection's own metrics, over what the stream has reported so far. */
data class MutationScore(val counts: Map<MutantStatus, Int>) {
    val defeated: Int get() = counts.filterKeys { it.isDefeated }.values.sum()
    val considered: Int get() = counts.filterKeys { !it.isExcluded }.values.sum()
    private val covered: Int get() = considered - (counts[MutantStatus.NOT_COVERED] ?: 0)

    val escaped: Int get() = counts[MutantStatus.ESCAPED] ?: 0

    /** Mutation Score Indicator, 0..100, or null before anything counts. */
    val msi: Int? get() = percent(defeated, considered)

    val coveredMsi: Int? get() = percent(defeated, covered)

    private fun percent(part: Int, whole: Int): Int? = if (whole <= 0) null else part * 100 / whole
}

/** One Infection process and everything its stream has told so far. Written by the stream, read by the UI. */
class TestoMutationRun(
    val title: String,
    /** The archived Testo run whose reports this mutates. */
    val sourceRunDir: Path,
    /** This run's own directory in the Testo run's archive (see [TestoMutationArchive]). */
    val workDir: Path,
    private val toLocalPath: (String) -> String?,
) {
    val htmlReport: Path get() = workDir.resolve(TestoInfectionLaunch.HTML_REPORT)

    val files: MutableList<MutatedFile> = CopyOnWriteArrayList()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Everything that is not a service message: Infection's banner, summary, and any error it dies with. */
    private val log = StringBuffer()

    @Volatile
    var expected: Int = 0

    @Volatile
    var exitCode: Int? = null

    @Volatile
    var stopRequested = false

    @Volatile
    var startedAt: Long = System.currentTimeMillis()

    @Volatile
    var finishedAt: Long? = null

    /** How to run Infection again over the same reports: known to the tab that started it, or to the Testo tab of a restored run. */
    @Volatile
    internal var recipe: TestoMutationRecipe? = null

    /** Some of its mutants are being run again, one Infection process each. */
    @Volatile
    var rerunning = false

    @Volatile
    internal var rerunStopRequested = false

    @Volatile
    internal var stopper: (() -> Unit)? = null

    val isRunning: Boolean get() = finishedAt == null

    val isBusy: Boolean get() = isRunning || rerunning

    /** SHA-256 of each mutated file as it was when Infection read it, by the interpreter's path: how a change is told. */
    internal val fingerprints = ConcurrentHashMap<String, String>()

    /** Remembers what [file] holds now; called as Infection announces it, never on a replay. */
    internal fun fingerprint(file: MutatedFile) {
        localPath(file.path)?.let { fingerprintOf(Path.of(it)) }?.let { fingerprints[file.path] = it }
    }

    val mutants: List<Mutant> get() = files.flatMap { it.mutants }

    fun localPath(path: String): String? = toLocalPath(path)

    fun score(): MutationScore = MutationScore(mutants.mapNotNull { it.status }.groupingBy { it }.eachCount())

    fun finishedCount(): Int = mutants.count { it.finished }

    /**
     * The score of the mutated files at [localPath], a host path: the file itself, or everything beneath a directory.
     * Null when none of this run's files is there.
     */
    fun scoreUnder(localPath: String, directory: Boolean): MutationScore? {
        val target = FileUtil.toSystemIndependentName(localPath).trimEnd('/')
        val under = files.filter { file ->
            val local = localPath(file.path)?.let(FileUtil::toSystemIndependentName) ?: return@filter false
            if (directory) local.startsWith("$target/") else local == target
        }
        if (under.isEmpty()) return null
        return MutationScore(under.flatMap { it.mutants }.mapNotNull { it.status }.groupingBy { it }.eachCount())
    }

    fun log(): String = log.toString()

    internal fun appendLog(line: String) {
        log.append(line).append('\n')
    }

    fun stop() {
        if (isRunning) stopRequested = true
        if (rerunning) rerunStopRequested = true
        stopper?.invoke()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    internal fun changed() = listeners.forEach { it() }

    /** A run read back from the archive: finished, with the times and outcome it had. */
    internal fun restore(startedAt: Long, finishedAt: Long, exitCode: Int?, stopped: Boolean, expected: Int) {
        this.startedAt = startedAt
        this.exitCode = exitCode
        this.stopRequested = stopped
        if (expected > 0) this.expected = expected
        this.finishedAt = finishedAt
    }

    internal fun finish(exitCode: Int?) {
        this.exitCode = exitCode
        finishedAt = System.currentTimeMillis()
        changed()
    }

    fun elapsedMs(): Long = (finishedAt ?: System.currentTimeMillis()) - startedAt
}

internal data class InfectionLocation(val file: String, val start: Int?, val end: Int?)

/** `infection://<file>::<start>-<end>` (offsets of the mutated code) and `file://<file>`, the protocol stripped. */
internal fun parseInfectionLocation(hint: String): InfectionLocation? {
    val protocol = hint.substringBefore("://", "")
    val path = hint.substringAfter("://")
    return when (protocol) {
        "file" -> path.takeIf { it.isNotEmpty() }?.let { InfectionLocation(it, null, null) }
        "infection" -> {
            val separator = path.lastIndexOf("::")
            if (separator <= 0) null
            else {
                val range = path.substring(separator + 2)
                InfectionLocation(
                    path.substring(0, separator),
                    range.substringBefore('-').toIntOrNull(),
                    range.substringAfter('-', "").toIntOrNull(),
                )
            }
        }
        else -> null
    }
}

/** A mutation run as the history lists it: live off the run, or off an archived run's summary. */
internal data class TestoMutationHistoryEntry(
    val dir: Path,
    val startedAt: Long,
    val elapsedMs: Long,
    val msi: Int?,
    val escaped: Int,
    val mutants: Int,
    val finished: Int,
    val running: Boolean,
    val stopped: Boolean,
    val exitCode: Int?,
) {
    val verdict: Icon? get() = mutationVerdict(running, stopped, escaped, exitCode, mutants)

    companion object {
        fun of(run: TestoMutationRun): TestoMutationHistoryEntry {
            val score = run.score()
            return TestoMutationHistoryEntry(
                run.workDir, run.startedAt, run.elapsedMs(), score.msi, score.escaped, maxOf(run.expected, run.mutants.size),
                run.finishedCount(), run.isRunning, run.stopRequested, run.exitCode,
            )
        }

        fun of(dir: Path, summary: TestoMutationArchive.Summary) = TestoMutationHistoryEntry(
            dir, summary.startedAt, summary.finishedAt - summary.startedAt, summary.msi, summary.escaped, summary.mutants,
            summary.mutants, false, summary.stopped, summary.exitCode,
        )
    }
}

/** The icon a run is judged by, or null while it runs. */
internal fun mutationVerdict(running: Boolean, stopped: Boolean, escaped: Int, exitCode: Int?, mutants: Int): Icon? = when {
    running -> null
    stopped -> TestoIcons.Status.FAILURE_CANCELLED
    escaped > 0 || (exitCode != 0 && mutants == 0) -> TestoIcons.Status.FAILURE
    else -> TestoIcons.Status.SUCCESS
}

/** SHA-256 of [file]'s bytes, or null when it cannot be read. */
internal fun fingerprintOf(file: Path): String? = runCatching {
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
}.getOrNull()

/** A file's line starts by byte offset: Infection's offsets count bytes, so they are resolved on the file's bytes. */
internal class TestoByteLines(private val bytes: ByteArray) {
    private val starts: IntArray = buildList {
        add(0)
        bytes.forEachIndexed { i, b -> if (b == '\n'.code.toByte()) add(i + 1) }
    }.toIntArray()

    /** 0-based line and column, in characters, of [offset]. */
    fun position(offset: Int): Pair<Int, Int> {
        val bounded = offset.coerceIn(0, bytes.size)
        val found = starts.binarySearch(bounded)
        val line = if (found >= 0) found else -found - 2
        val start = starts[line]
        return line to String(bytes, start, bounded - start, Charsets.UTF_8).length
    }
}
