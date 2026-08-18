package com.github.xepozz.testo.tests.run

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import java.nio.file.Path

/**
 * The `--log-html` / `--log-junit` reports written into an IDE-managed folder on every run, uniform with how the
 * Coverage run points its `--coverage-*` flags at an IDE-controlled path: Testo writes there, announces the report,
 * and the run archive copies it into history. One stable location per run configuration, overwritten each run — the
 * announcement's mtime-vs-run-start gate ignores a stale file a stopped run left behind.
 */
internal object TestoReportFlags {
    /** HTML as a single self-contained `.html` file: one artifact to archive, and it opens over `file://` in a tab. */
    fun htmlReportFile(project: Project, configurationName: String): Path =
        reportDir(project, configurationName).resolve("report.html")

    fun junitReportFile(project: Project, configurationName: String): Path =
        reportDir(project, configurationName).resolve("junit.xml")

    /** The `--log-*` tokens for the enabled reports, each pointing at the given local path. Pure. */
    fun reportFlagArguments(logHtml: Boolean, logJunit: Boolean, htmlPath: String, junitPath: String): List<String> =
        buildList {
            if (logHtml) add("--log-html=$htmlPath")
            if (logJunit) add("--log-junit=$junitPath")
        }

    private fun reportDir(project: Project, configurationName: String): Path = Path.of(
        PathManager.getSystemPath(),
        "testo",
        "reports",
        project.locationHash,
        FileUtil.sanitizeFileName(configurationName).ifEmpty { "run" },
    )
}
