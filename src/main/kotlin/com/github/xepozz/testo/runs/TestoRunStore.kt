package com.github.xepozz.testo.runs

import com.google.gson.Gson
import com.google.gson.JsonObject
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
 * The Testo run archive: one directory per run under the IDE system dir, holding the raw teamcity output
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

    /**
     * Complete runs (manifest present) the user has not thrown away, newest first. Touches the filesystem — call off
     * the EDT.
     */
    fun listRuns(): List<Pair<Path, TestoRunManifest>> = runDirectories()
        .mapNotNull { dir -> readManifest(dir)?.let { dir to it } }
        .filter { it.second.retention != RunRetention.DISCARD }
        .sortedByDescending { it.second.startedAt }

    /** The archived run's retention, or null while the run is still in flight — its choice rides on the recording. */
    fun retentionOf(dir: Path): RunRetention? = readManifest(dir)?.retention

    /** Rewrites the manifest's retention; a no-op while the run is still in flight. */
    fun setRetention(dir: Path, retention: RunRetention) {
        val manifest = readManifest(dir) ?: return
        runCatching { writeManifest(dir, manifest.copy(retention = retention)) }
            .onFailure { LOG.warn("Failed to set retention of $dir", it) }
    }

    /**
     * Unpacks an exported run into the archive and returns it. The directory is named after the run it holds, so it
     * sorts with the rest; the manifest is what decides the zip was one of ours at all.
     *
     * An imported run comes in locked: it was carried here by hand, often from another machine, and rotation deleting
     * it after ten local runs would throw away the one copy that exists.
     */
    fun importRun(zip: Path): Pair<Path, TestoRunManifest>? {
        val staging = root().resolve("import-${System.currentTimeMillis()}")
        return runCatching {
            unzipRunDirectory(zip, staging)
            val imported = readManifest(staging) ?: run {
                FileUtil.delete(staging)
                return null
            }
            val manifest = imported.copy(retention = RunRetention.LOCKED)
            val target = freeDirectory(manifest)
            Files.move(staging, target)
            writeManifest(target, manifest)
            target to manifest
        }.onFailure {
            LOG.warn("Failed to import a Testo run from $zip", it)
            runCatching { FileUtil.delete(staging) }
        }.getOrNull()
    }

    private fun freeDirectory(manifest: TestoRunManifest): Path {
        val stem = "${manifest.startedAt}-${FileUtil.sanitizeFileName(manifest.configurationName)}"
        var candidate = root().resolve(stem)
        var index = 2
        while (candidate.exists()) candidate = root().resolve("$stem-${index++}")
        return candidate
    }

    private fun writeManifest(dir: Path, manifest: TestoRunManifest) {
        Files.writeString(dir.resolve(TestoRunRecording.MANIFEST_FILE), gson.toJson(manifest), StandardCharsets.UTF_8)
    }

    fun readManifest(dir: Path): TestoRunManifest? = runCatching {
        val file = dir.resolve(TestoRunRecording.MANIFEST_FILE)
        if (!file.exists()) return null
        // Every field has a default, so Gson deserializes a bare `{}` — or any foreign JSON object — into a
        // valid-looking v=VERSION manifest. Require `v` to be spelled out; a manifest we wrote always carries it.
        val root = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject::class.java) ?: return null
        if (!root.has("v") || root.get("v").asInt < 1) return null
        gson.fromJson(root, TestoRunManifest::class.java)
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
        val rotating = ArrayList<Pair<Path, Long>>()
        for (dir in runDirectories()) {
            val manifest = readManifest(dir)
            when {
                manifest == null ->
                    // A directory that never got its manifest: the run crashed or the IDE died mid-write.
                    if (now - startedAtOf(dir) > INCOMPLETE_GRACE_MS) delete(dir)
                manifest.retention == RunRetention.DISCARD -> delete(dir)
                manifest.retention == RunRetention.LOCKED -> Unit
                else -> rotating += dir to manifest.startedAt
            }
        }
        rotating.sortedByDescending { it.second }.drop(keep).forEach { delete(it.first) }
    }

    /**
     * Clears the history. Touches the filesystem — call off the EDT.
     *
     * @param keepLocked leave [RunRetention.LOCKED] runs where they are.
     * @param spare the run a tab is currently showing: it is marked [RunRetention.DISCARD] rather than deleted, so the
     *        open tab keeps the files it is built on — it leaves the history now and the disk at the next prune, and
     *        setting *Keep* on that tab brings it back.
     */
    fun clearHistory(keepLocked: Boolean, spare: Path?) {
        val spared = spare?.let { runCatching { it.toAbsolutePath().normalize() }.getOrNull() }
        val now = System.currentTimeMillis()
        for (dir in runDirectories()) {
            val retention = retentionOf(dir)
            // No manifest yet = possibly another tab's run still being written — same grace as prune().
            if (retention == null && now - startedAtOf(dir) <= INCOMPLETE_GRACE_MS) continue
            if (keepLocked && retention == RunRetention.LOCKED) continue
            if (dir.toAbsolutePath().normalize() == spared) {
                setRetention(dir, RunRetention.DISCARD)
                continue
            }
            delete(dir)
        }
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
