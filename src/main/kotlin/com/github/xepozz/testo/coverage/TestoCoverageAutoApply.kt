package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.perTest.TestoCoverageKeys
import com.github.xepozz.testo.tests.TestoConsoleProperties
import com.github.xepozz.testo.tests.console.TestoReportRef
import com.github.xepozz.testo.tests.console.resolveCoverageDataFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * One report per format out of everything the run announced — a flag-requested report beats a `testo.php`-configured
 * one of the same format (the IDE controls its path; the config one belongs to the project), later announcements beat
 * earlier ones otherwise. Pure; keyed by the resolved local path, normalized like every other coverage key.
 */
fun dedupeCoverageByFormat(
    resolved: List<Pair<TestoReportRef, Path>>,
    flagPaths: Set<String>,
): List<Pair<TestoReportRef, Path>> =
    resolved
        .groupBy { it.first.coverageFormat }
        .mapNotNull { (format, group) ->
            if (format == null) return@mapNotNull null
            group.lastOrNull { TestoCoverageKeys.normalize(it.second.toString()) in flagPaths } ?: group.last()
        }

/**
 * On process exit: applies the run's announced coverage reports through the same [applyTestoCoverage] the grouped
 * button uses, honoring its checkboxes — no click needed.
 */
internal fun autoApplyCoverage(project: Project, props: TestoConsoleProperties, flagLocalPaths: List<Path>) {
    ApplicationManager.getApplication().executeOnPooledThread {
        val mapToLocal: (String) -> String? = { runCatching { props.pathMapper.getLocalPath(it) }.getOrNull() }
        val writtenAfter = props.reportStore.runStartedAt
        val resolved = props.reportStore.coverage().mapNotNull { ref ->
            resolveCoverageDataFile(ref, project, mapToLocal, writtenAfter)?.let { ref to it }
        }
        val flagKeys = flagLocalPaths.map { TestoCoverageKeys.normalize(it.toString()) }.toSet()
        val chosen = dedupeCoverageByFormat(resolved, flagKeys)
            .filter { props.reportStore.isCoverageChecked(it.first.path) }
            .map { (ref, path) -> TestoCoverageReport(ref.name, ref.coverageFormat, path) }
        if (chosen.isEmpty()) return@executeOnPooledThread
        ApplicationManager.getApplication().invokeLater({ applyTestoCoverage(project, chosen) }, project.disposed)
    }
}

/** The local file each enabled flag makes Testo write — for coverage-xml, the `index.xml` the loader consumes. */
internal fun flagLocalDataFiles(flags: List<Pair<CoverageFormat, String>>): List<Path> = flags.map { (format, local) ->
    val path = Path.of(local)
    if (format == CoverageFormat.COVERAGE_XML) path.resolve("index.xml") else path
}
