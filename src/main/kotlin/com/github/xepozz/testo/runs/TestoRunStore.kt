package com.github.xepozz.testo.runs

import com.google.gson.Gson
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The Testo run archive (arch §10a): one directory per run under the IDE system dir, holding the raw teamcity output
 * (`output.log`), the metadata (`run.json`) and the captured report files (`reports/`). The whole console — channels,
 * statuses, node tree, report buttons — is built by parsing that stream, so replaying it through the live converter
 * reconstructs the run exactly; nothing else needs persisting.
 *
 * Retention is the plugin's own (the platform's 10-file history rotation deletes with a bare `FileUtil.delete`, no
 * event to hook): the newest [retentionLimit] *complete* runs are kept, plus incomplete directories younger than a
 * day — a run in flight looks incomplete until its manifest lands.
 */
@Service(Service.Level.PROJECT)
class TestoRunStore(private val project: Project) {
    private val gson = Gson()

    fun root(): Path = Path.of(PathManager.getSystemPath(), "testo", "runs", project.locationHash)

    fun beginRun(configurationName: String, executorId: String): TestoRunRecording {
        val startedAt = System.currentTimeMillis()
        val dir = root().resolve("$startedAt-${FileUtil.sanitizeFileName(configurationName)}")
        Files.createDirectories(dir)
        return TestoRunRecording(dir, configurationName, executorId, startedAt)
    }

    /** Complete runs (manifest present), newest first. Touches the filesystem — call off the EDT. */
    fun listRuns(): List<Pair<Path, TestoRunManifest>> = runDirectories()
        .mapNotNull { dir -> readManifest(dir)?.let { dir to it } }
        .sortedByDescending { it.second.startedAt }

    fun readManifest(dir: Path): TestoRunManifest? = runCatching {
        val file = dir.resolve(TestoRunRecording.MANIFEST_FILE)
        if (!file.exists()) return null
        gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), TestoRunManifest::class.java)
            ?.takeIf { it.v >= 1 }
    }.getOrNull()

    /** The tests the run announced, as [normalizeRunLocation] keys. Empty for a v1 archive, which recorded none. */
    fun readLocations(dir: Path): Set<String> = runCatching {
        val file = dir.resolve(TestoRunRecording.TESTS_FILE)
        if (!file.exists()) return emptySet()
        Files.readAllLines(file, StandardCharsets.UTF_8).filterTo(LinkedHashSet()) { it.isNotBlank() }
    }.getOrDefault(emptySet())

    /** Streams `output.log` back in recorded order. Skips lines that fail to parse rather than aborting the replay. */
    fun readChunks(dir: Path, consumer: (stream: Int, text: String) -> Unit) {
        val file = dir.resolve(TestoRunRecording.OUTPUT_FILE)
        if (!file.exists()) return
        Files.newBufferedReader(file, StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val chunk = runCatching { gson.fromJson(line, TestoRunRecording.Chunk::class.java) }.getOrNull() ?: continue
                consumer(chunk.s, chunk.t)
            }
        }
    }

    /** Applies retention. Called after each archived run, off the EDT. */
    fun prune() {
        val keep = retentionLimit()
        val now = System.currentTimeMillis()
        val complete = ArrayList<Pair<Path, Long>>()
        for (dir in runDirectories()) {
            val manifest = readManifest(dir)
            if (manifest != null) {
                complete += dir to manifest.startedAt
            } else if (now - startedAtOf(dir) > INCOMPLETE_GRACE_MS) {
                // A directory that never got its manifest: the run crashed or the IDE died mid-write.
                delete(dir)
            }
        }
        complete.sortedByDescending { it.second }.drop(keep).forEach { delete(it.first) }
    }

    /** Drops every archived run of this project. Touches the filesystem — call off the EDT. */
    fun clear() {
        runDirectories().forEach { delete(it) }
    }

    private fun runDirectories(): List<Path> = runCatching {
        Files.list(root()).use { stream -> stream.filter { it.isDirectory() }.toList() }
    }.getOrDefault(emptyList())

    private fun startedAtOf(dir: Path): Long =
        dir.name.substringBefore('-').toLongOrNull()
            ?: runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(0L)

    private fun delete(dir: Path) {
        runCatching { FileUtil.delete(dir) }.onFailure { LOG.warn("Failed to delete archived Testo run $dir", it) }
    }

    companion object {
        private val LOG = Logger.getInstance(TestoRunStore::class.java)
        private const val RETENTION_KEY = "testo.runs.retention"
        private const val RETENTION_DEFAULT = 10
        private const val INCOMPLETE_GRACE_MS = 24L * 60 * 60 * 1000

        fun getInstance(project: Project): TestoRunStore = project.getService(TestoRunStore::class.java)

        fun retentionLimit(): Int =
            PropertiesComponent.getInstance().getInt(RETENTION_KEY, RETENTION_DEFAULT).coerceAtLeast(1)

        fun setRetentionLimit(limit: Int) =
            PropertiesComponent.getInstance().setValue(RETENTION_KEY, limit, RETENTION_DEFAULT)
    }
}
