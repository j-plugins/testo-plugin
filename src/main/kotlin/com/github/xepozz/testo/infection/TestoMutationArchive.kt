package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.runs.TestoRunStore
import com.google.gson.Gson
import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicBoolean

/** Each file's score from the summary that judged it last, keyed by host path. */
internal fun mergeScores(summaries: List<TestoMutationArchive.Summary>): Map<String, MutationScore> {
    val latest = HashMap<String, TestoMutationArchive.FileScore>()
    for (summary in summaries) {
        for ((path, score) in summary.scores) {
            val local = FileUtil.toSystemIndependentName(summary.localPaths[path] ?: path)
            if ((latest[local]?.at ?: Long.MIN_VALUE) <= score.at) latest[local] = score
        }
    }
    return latest.mapValues { it.value.score() }
}

/** The score of [localPath], a host path: the file itself, or every scored file beneath a directory; null when none is there. */
internal fun scoreUnder(scores: Map<String, MutationScore>, localPath: String, directory: Boolean): MutationScore? {
    val target = FileUtil.toSystemIndependentName(localPath).trimEnd('/')
    if (!directory) return scores[target]
    val under = scores.filterKeys { it.startsWith("$target/") }.values
    if (under.isEmpty()) return null
    return MutationScore(under.flatMap { it.counts.entries }.groupingBy { it.key }.fold(0) { sum, entry -> sum + entry.value })
}

/**
 * Mutation runs kept beside the Testo run they mutate, in `<run dir>/infection/<started at>/`: they are exported,
 * locked and pruned with it. A run is its `stream.log` (every line Infection printed, the command line first) and the
 * `mutations.log` it wrote, read back through the same parsers the live run used; `mutation.json` holds what the
 * stream does not say.
 */
internal object TestoMutationArchive {
    const val DIR = "infection"
    const val STREAM_FILE = "stream.log"
    const val SUMMARY_FILE = "mutation.json"
    const val TEXT_LOG = "mutations.log"

    /** A run's reruns of single mutants, `<run>/reruns/<started at>/`: a stream and a text log each, replayed in order. */
    const val RERUNS_DIR = "reruns"

    /** How many mutation runs of one Testo run are kept; the oldest go when a new one starts. */
    const val KEEP = 5

    private val gson = Gson()
    private val inputUsers = HashMap<Path, Int>()
    private val deleting = HashSet<Path>()

    /**
     * Protects inputs before preparation, including the interval before an uncertain result is saved. Refuses a directory
     * whose Testo run is already being deleted.
     */
    @Synchronized
    fun protectInputs(dir: Path): AutoCloseable {
        val path = dir.toAbsolutePath().normalize()
        if (deleting.any { path.startsWith(it) }) throw ExecutionException(TestoBundle.message("infection.error.runDeleted"))
        inputUsers[path] = inputUsers.getOrDefault(path, 0) + 1
        val released = AtomicBoolean()
        return AutoCloseable {
            synchronized(this) {
                if (released.compareAndSet(false, true)) {
                    val remaining = inputUsers.getValue(path) - 1
                    if (remaining == 0) inputUsers.remove(path) else inputUsers[path] = remaining
                }
            }
        }
    }

    private fun hasInputUsers(dir: Path): Boolean {
        val path = dir.toAbsolutePath().normalize()
        return inputUsers.keys.any { it.startsWith(path) }
    }

    private fun mustKeep(dir: Path): Boolean {
        if (hasInputUsers(dir)) return true
        // No finished summary: a process the IDE died under may still read the inputs — for hours, not for days.
        val now = System.currentTimeMillis()
        val state = summary(dir)?.takeIf { it.finishedAt > 0 }
            ?: return now - TestoRunStore.startedAtOf(dir) <= TestoRunStore.INCOMPLETE_GRACE_MS
        return state.unconfirmedReason != null && now - state.finishedAt <= TestoRunStore.INCOMPLETE_GRACE_MS
    }

    fun deleteRunIfSafe(sourceRunDir: Path): Boolean {
        if (!claimForDeletion(sourceRunDir)) return false
        deleteClaimed(sourceRunDir)
        return true
    }

    /**
     * Decides under the lock that input acquisition and summary publication share, and marks the run so neither can
     * follow; the files go in [deleteClaimed], outside it, so a reader waits for the check alone. An unlistable archive
     * fails closed.
     */
    @Synchronized
    internal fun claimForDeletion(sourceRunDir: Path): Boolean {
        val path = sourceRunDir.toAbsolutePath().normalize()
        if (path in deleting || hasInputUsers(sourceRunDir)) return false
        val root = sourceRunDir.resolve(DIR)
        if (!Files.notExists(root)) {
            val protected = runCatching {
                Files.list(root).use { entries -> entries.anyMatch { Files.isDirectory(it) && mustKeep(it) } }
            }.getOrDefault(true)
            if (protected) return false
        }
        deleting.add(path)
        return true
    }

    internal fun deleteClaimed(sourceRunDir: Path) {
        try {
            NioFiles.deleteRecursively(sourceRunDir)
        } finally {
            synchronized(this) { deleting.remove(sourceRunDir.toAbsolutePath().normalize()) }
        }
    }

    class Summary(
        val title: String = "",
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        val exitCode: Int? = null,
        val stopped: Boolean = false,
        val rerunStopped: Boolean = false,
        val failureReason: String? = null,
        val unconfirmedReason: String? = null,
        val expected: Int = 0,
        /** Interpreter path → host path of every mutated file, as the interpreter's mappings resolved it then. */
        val localPaths: Map<String, String> = emptyMap(),
        val msi: Int? = null,
        val escaped: Int = 0,
        val mutants: Int = 0,
        /** SHA-256 of each mutated file when it was mutated, by interpreter path. */
        val fingerprints: Map<String, String> = emptyMap(),
        /** Each file whose mutants all finished, by interpreter path: what the Coverage view scores it by. */
        val scores: Map<String, FileScore> = emptyMap(),
    )

    /** A file's mutants by status name, and when they were last judged: the run's start, or its latest rerun of them. */
    class FileScore(val counts: Map<String, Int> = emptyMap(), val at: Long = 0) {
        fun score() = MutationScore(counts.mapNotNull { (name, count) -> MutantStatus.entries.firstOrNull { it.name == name }?.to(count) }.toMap())
    }

    fun newRunDir(testoRunDir: Path, startedAt: Long): Path = testoRunDir.resolve(DIR).resolve(startedAt.toString())

    // Two reruns can start within one millisecond when the first fails at once; the name is their order, so it stays unique.
    fun newRerunDir(workDir: Path, startedAt: Long): Path {
        val root = workDir.resolve(RERUNS_DIR)
        var at = startedAt
        while (Files.exists(root.resolve(at.toString()))) at++
        return root.resolve(at.toString())
    }

    private fun reruns(workDir: Path): List<Path> {
        val root = workDir.resolve(RERUNS_DIR)
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { it.toList() }.sortedBy { it.fileName.toString().toLongOrNull() ?: 0 }
    }

    /** The mutation runs of [testoRunDir], oldest first. */
    fun runs(testoRunDir: Path): List<Path> {
        val root = testoRunDir.resolve(DIR)
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it.resolve(SUMMARY_FILE)) }.toList()
        }.sortedBy { it.fileName.toString().toLongOrNull() ?: 0 }
    }

    @Synchronized
    fun prune(testoRunDir: Path, keep: Int = KEEP) {
        val root = testoRunDir.resolve(DIR)
        if (!Files.isDirectory(root)) return
        val all = Files.list(root).use { it.toList() }.sortedBy { it.fileName.toString().toLongOrNull() ?: 0 }
        all.dropLast(keep).filter { !mustKeep(it) }
            .forEach { runCatching { NioFiles.deleteRecursively(it) } }
    }

    /** A mutation run of [testoRunDir] whose process may still be alive: its stop went unconfirmed within the grace period. */
    @Synchronized
    fun unconfirmedRun(testoRunDir: Path): Path? = runs(testoRunDir).firstOrNull { dir ->
        val state = summary(dir) ?: return@firstOrNull false
        state.unconfirmedReason != null && System.currentTimeMillis() - state.finishedAt <= TestoRunStore.INCOMPLETE_GRACE_MS
    }

    class Recorder(private val dir: Path) : AutoCloseable {
        private val writer: Writer

        init {
            Files.createDirectories(dir)
            writer = Files.newBufferedWriter(dir.resolve(STREAM_FILE), StandardCharsets.UTF_8)
        }

        @Synchronized
        fun line(line: String) {
            writer.write(line)
            writer.write("\n")
        }

        fun summary(run: TestoMutationRun) = writeSummary(dir, run)

        @Synchronized
        override fun close() = writer.close()
    }

    /** Writes [run]'s summary; the files in [rescored] were just judged again, the rest keep when they last were. */
    @Synchronized
    fun writeSummary(dir: Path, run: TestoMutationRun, rescored: Set<String> = emptySet()) {
        val score = run.score()
        val previous = summary(dir)?.scores.orEmpty()
        val now = System.currentTimeMillis()
        val scores = run.files
            .filter { file -> file.mutants.isNotEmpty() && file.mutants.all { it.finished && it.status != null } }
            .associate { file ->
                val at = if (file.path in rescored) now else previous[file.path]?.at ?: run.startedAt
                file.path to FileScore(file.mutants.groupingBy { it.status!!.name }.eachCount(), at)
            }
        val summary = Summary(
            title = run.title,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt ?: System.currentTimeMillis(),
            exitCode = run.exitCode,
            stopped = run.stopRequested,
            rerunStopped = run.rerunStopRequested,
            failureReason = run.failureReason,
            unconfirmedReason = run.unconfirmedReason,
            expected = run.expected,
            localPaths = run.files.mapNotNull { file -> run.localPath(file.path)?.let { file.path to it } }.toMap(),
            msi = score.msi,
            escaped = score.escaped,
            mutants = run.mutants.size,
            fingerprints = HashMap(run.fingerprints),
            scores = scores,
        )
        val temporary = Files.createTempFile(dir, ".mutation-", ".tmp")
        try {
            Files.writeString(temporary, gson.toJson(summary), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, dir.resolve(SUMMARY_FILE), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // Readers and deletion are still serialized on providers without atomic rename.
                Files.move(temporary, dir.resolve(SUMMARY_FILE), REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Synchronized
    fun summary(dir: Path): Summary? = runCatching {
        gson.fromJson(Files.readString(dir.resolve(SUMMARY_FILE)), Summary::class.java)
    }.getOrNull()

    /** A finished run back from [dir], or null when it is not one. */
    fun load(testoRunDir: Path, dir: Path): TestoMutationRun? {
        val summary = summary(dir) ?: return null
        val run = TestoMutationRun(summary.title, testoRunDir, dir) { path ->
            summary.localPaths[path] ?: path.takeIf { Files.exists(Path.of(it)) }
        }
        replay(dir, TestoMutationStream(run), run)
        reruns(dir).forEach { replay(it, TestoMutationStream(run, rerun = true), run) }
        run.fingerprints.putAll(summary.fingerprints)
        run.restore(summary.startedAt, summary.finishedAt, summary.exitCode, summary.stopped, summary.expected,
            summary.rerunStopped, summary.failureReason, summary.unconfirmedReason)
        return run
    }

    /**
     * The score of every file the mutation runs of [testoRunDir] judged, by host path: each file by whichever run judged
     * it last, so a run narrowed to one file or a rerun of one mutant updates that file and leaves the others as they were.
     */
    fun scores(testoRunDir: Path): Map<String, MutationScore> = mergeScores(runs(testoRunDir).mapNotNull { dir ->
        val summary = summary(dir) ?: return@mapNotNull null
        // Written before the per-file scores were: read the run back and store them, once.
        if (summary.scores.isEmpty() && summary.mutants > 0) {
            load(testoRunDir, dir)?.let { run -> runCatching { writeSummary(dir, run) } }
            summary(dir) ?: summary
        } else summary
    })

    private fun replay(dir: Path, stream: TestoMutationStream, run: TestoMutationRun) {
        runCatching {
            Files.newBufferedReader(dir.resolve(STREAM_FILE), StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { stream.feed("$it\n", stdout = true) }
            }
        }
        stream.flush()
        applyTextLog(dir.resolve(TEXT_LOG), run)
    }

    /** Gives every mutant the code and test output Infection's text log holds for it. */
    fun applyTextLog(file: Path, run: TestoMutationRun) {
        if (!Files.isRegularFile(file)) return
        val entries = runCatching { TestoMutationTextLog.parse(Files.readString(file)) }.getOrNull() ?: return
        run.mutants.forEach { mutant ->
            val entry = entries[mutant.hash] ?: return@forEach
            // Over the stream's own snippet of an escaped mutant too: the line numbers are counted on this diff.
            mutant.original = entry.original
            mutant.mutated = entry.mutated
            mutant.firstLine = entry.firstLine
            mutant.lines = entry.line?.let { it until it + entry.span }
            mutant.output = entry.output
        }
    }
}
