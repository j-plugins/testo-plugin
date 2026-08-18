package com.github.xepozz.testo.runs

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory

/**
 * An archived run as a single file: the run directory zipped whole (`run.json`, `output.log`, `tests.txt`, `reports/`),
 * which is also all an import needs — the same layout goes back under the archive root and replays like any other run.
 */
internal fun zipRunDirectory(source: Path, target: Path) {
    Files.createDirectories(target.parent)
    ZipOutputStream(BufferedOutputStream(Files.newOutputStream(target))).use { zip ->
        Files.walk(source).use { paths ->
            paths.filter { !it.isDirectory() }.forEach { file ->
                val name = source.relativize(file).joinToString("/")
                zip.putNextEntry(ZipEntry(name))
                Files.copy(file, zip)
                zip.closeEntry()
            }
        }
    }
}

/**
 * Unpacks an exported run into [target]. Entries that would land outside it are skipped — a zip is a file like any
 * other, and this one may not have been written by us.
 */
internal fun unzipRunDirectory(zip: Path, target: Path) {
    val root = target.toAbsolutePath().normalize()
    Files.createDirectories(root)
    ZipInputStream(Files.newInputStream(zip)).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val resolved = root.resolve(entry.name).normalize()
            if (!resolved.startsWith(root)) continue
            if (entry.isDirectory) {
                Files.createDirectories(resolved)
            } else {
                Files.createDirectories(resolved.parent)
                Files.copy(input, resolved, StandardCopyOption.REPLACE_EXISTING)
            }
            input.closeEntry()
        }
    }
}

/**
 * Whether the announced report is the entry of a directory rather than a file of its own.
 *
 * Testo's reports come in both shapes and the announcement always names the entry (`docs/spec/html-report.md`): an
 * HTML report is either a self-contained `report.html` or `index.html` beside its assets, and coverage-xml is always
 * `index.xml` in a directory. The `index.` prefix is what tells them apart — and a directory report has to be copied
 * whole, or the archived copy opens without its assets.
 */
internal fun isDirectoryReport(local: Path): Boolean =
    local.fileName?.toString()?.startsWith("index.", ignoreCase = true) == true

/** What a captured report is called under `reports/`: the format alone for a directory, plus the extension for a file. */
internal fun capturedReportName(stem: String, local: Path): String {
    if (isDirectoryReport(local)) return stem
    val extension = local.fileName?.toString()?.substringAfterLast('.', "").orEmpty()
    return if (extension.isEmpty()) stem else "$stem.$extension"
}

/** The name an exported run is offered under: readable, and unique enough to sit in a downloads folder. */
internal fun exportFileName(manifest: TestoRunManifest): String {
    val name = manifest.configurationName.ifEmpty { "testo-run" }
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-')
        .ifEmpty { "testo-run" }
    return "$name-${manifest.startedAt}"
}
