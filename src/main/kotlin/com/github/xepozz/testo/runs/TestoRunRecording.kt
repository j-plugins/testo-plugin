package com.github.xepozz.testo.runs

import com.google.gson.Gson
import java.io.Writer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live run being written into its archive directory. The output stream is framed as JSON-lines
 * (`{"s":1,"t":"…"}`, `s` = 1 stdout / 2 stderr / 3 system) so a replay can re-emit the exact chunks in order; Gson's
 * escaping keeps one chunk on one line whatever the text holds. Chunks come off the process-output thread, the
 * finalizer off a pooled one — hence the lock.
 */
class TestoRunRecording internal constructor(
    val dir: Path,
    val configurationName: String,
    val executorId: String,
    val startedAt: Long,
) {
    private val lock = Any()
    private val gson = Gson()
    private var writer: Writer? = null
    private var closed = false
    private val finishing = AtomicBoolean()
    private val locations = LinkedHashSet<String>()

    /** What the tab's *Replay* group says to do with this run once it is archived. */
    @Volatile
    var retention: RunRetention = RunRetention.AUTO

    val reportsDir: Path get() = dir.resolve(REPORTS_DIR)

    fun appendChunk(stream: Int, text: String) {
        synchronized(lock) {
            if (closed) return
            val target = writer
                ?: Files.newBufferedWriter(dir.resolve(OUTPUT_FILE), StandardCharsets.UTF_8).also { writer = it }
            gson.toJson(Chunk(stream, text), target)
            target.write("\n")
        }
    }

    fun closeOutput() {
        synchronized(lock) {
            closed = true
            runCatching { writer?.close() }
            writer = null
        }
    }

    /** True for exactly one caller — the run is finalized from more than one termination hook. */
    fun tryBeginFinish(): Boolean = finishing.compareAndSet(false, true)

    /** Remembers a test this run announced, so the "Show history" lens can tell which archive holds it. */
    fun noteLocation(hint: String) {
        val key = normalizeRunLocation(hint)
        if (key.isEmpty()) return
        synchronized(lock) {
            if (locations.size < MAX_LOCATIONS) locations.add(key)
        }
    }

    fun writeManifest(manifest: TestoRunManifest) {
        Files.writeString(dir.resolve(MANIFEST_FILE), gson.toJson(manifest), StandardCharsets.UTF_8)
    }

    fun writeLocations() {
        val snapshot = synchronized(lock) { locations.toList() }
        if (snapshot.isEmpty()) return
        Files.write(dir.resolve(TESTS_FILE), snapshot, StandardCharsets.UTF_8)
    }

    /** One framed line of `output.log`. Short field names: there is a line per output chunk. */
    data class Chunk(val s: Int = STDOUT, val t: String = "")

    companion object {
        const val STDOUT = 1
        const val STDERR = 2
        const val SYSTEM = 3

        const val OUTPUT_FILE = "output.log"
        const val MANIFEST_FILE = "run.json"
        const val TESTS_FILE = "tests.txt"
        const val REPORTS_DIR = "reports"

        // A suite of a few thousand tests writes a few thousand short lines; the cap only stops a pathological run
        // (a data provider yielding tens of thousands of sets) from holding the whole list in memory.
        private const val MAX_LOCATIONS = 50_000
    }
}

/**
 * The form of a location hint the archive stores and the lens looks up: the test's own url, without the dataset
 * coordinates Testo appends. The lens asks about a method (`…::testFoo`), and every dataset of that method
 * (`…::testFoo#3`) has to answer for it — collapsing them here also keeps one entry per test rather than per dataset.
 */
internal fun normalizeRunLocation(hint: String): String =
    hint.substringBefore('#').substringBefore(" with data set").trim()
