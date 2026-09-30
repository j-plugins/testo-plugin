package com.github.xepozz.testo.infection

import com.google.gson.Gson
import com.intellij.openapi.util.io.NioFiles
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

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

    class Summary(
        val title: String = "",
        val startedAt: Long = 0,
        val finishedAt: Long = 0,
        val exitCode: Int? = null,
        val stopped: Boolean = false,
        val expected: Int = 0,
        /** Interpreter path → host path of every mutated file, as the interpreter's mappings resolved it then. */
        val localPaths: Map<String, String> = emptyMap(),
        val msi: Int? = null,
        val escaped: Int = 0,
        val mutants: Int = 0,
        /** SHA-256 of each mutated file when it was mutated, by interpreter path. */
        val fingerprints: Map<String, String> = emptyMap(),
    )

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

    fun prune(testoRunDir: Path, keep: Int = KEEP) {
        val root = testoRunDir.resolve(DIR)
        if (!Files.isDirectory(root)) return
        val all = Files.list(root).use { it.toList() }.sortedBy { it.fileName.toString().toLongOrNull() ?: 0 }
        all.dropLast(keep).forEach { runCatching { NioFiles.deleteRecursively(it) } }
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

    fun writeSummary(dir: Path, run: TestoMutationRun) {
        val score = run.score()
        val summary = Summary(
            title = run.title,
            startedAt = run.startedAt,
            finishedAt = run.finishedAt ?: System.currentTimeMillis(),
            exitCode = run.exitCode,
            stopped = run.stopRequested,
            expected = run.expected,
            localPaths = run.files.mapNotNull { file -> run.localPath(file.path)?.let { file.path to it } }.toMap(),
            msi = score.msi,
            escaped = score.escaped,
            mutants = run.mutants.size,
            fingerprints = HashMap(run.fingerprints),
        )
        Files.writeString(dir.resolve(SUMMARY_FILE), gson.toJson(summary), StandardCharsets.UTF_8)
    }

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
        run.restore(summary.startedAt, summary.finishedAt, summary.exitCode, summary.stopped, summary.expected)
        return run
    }

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
            if (mutant.original == null) {
                mutant.original = entry.original
                mutant.mutated = entry.mutated
            }
            mutant.output = entry.output
        }
    }
}
