package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.CoverageFormat
import com.github.xepozz.testo.coverage.format.childElements
import com.github.xepozz.testo.coverage.format.descendants
import com.github.xepozz.testo.coverage.format.readXmlRoot
import com.github.xepozz.testo.runs.TestoRunManifest
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Whether an archived run carries what Infection needs to skip its own initial test run. */
internal sealed interface TestoMutationReadiness {
    /** [coverageXml] is the coverage-xml directory (the one holding `index.xml`). */
    data class Ready(val coverageXml: Path, val junit: Path) : TestoMutationReadiness

    enum class Missing : TestoMutationReadiness {
        NOT_FINISHED,
        NOT_PASSED,
        NO_COVERAGE_XML,
        NO_JUNIT,
    }
}

internal object TestoInfectionReports {
    const val JUNIT_FORMAT = "junit"

    fun readiness(runDir: Path, manifest: TestoRunManifest?): TestoMutationReadiness {
        if (manifest == null) return TestoMutationReadiness.Missing.NOT_FINISHED
        // Infection trusts the reports blindly with --skip-initial-tests: a failing test would pass for a killed mutant.
        if (manifest.cancelled || manifest.exitCode != 0) return TestoMutationReadiness.Missing.NOT_PASSED
        val index = manifest.stored(CoverageFormat.COVERAGE_XML.id, runDir)?.takeIf(Files::isRegularFile)
            ?: return TestoMutationReadiness.Missing.NO_COVERAGE_XML
        val junit = manifest.stored(JUNIT_FORMAT, runDir)?.takeIf(Files::isRegularFile)
            ?: return TestoMutationReadiness.Missing.NO_JUNIT
        return TestoMutationReadiness.Ready(index.parent, junit)
    }

    private fun TestoRunManifest.stored(format: String, runDir: Path): Path? = reports
        .firstOrNull { it.format.equals(format, ignoreCase = true) && it.stored != null }
        ?.let { runDir.resolve(it.stored!!) }

    /**
     * The sources the run's tests executed at least one line of, relative to the project root as coverage-xml spells
     * them — the same on the host and inside a container.
     */
    fun coveredSourceFiles(coverageXml: Path): List<String> =
        readXmlRoot(coverageXml.resolve("index.xml")).descendants("file").filter { !isEvaluated(it) }.mapNotNull { file ->
            val executed = file.childElements("totals").firstOrNull()
                ?.childElements("lines")?.firstOrNull()
                ?.getAttribute("executed")?.toIntOrNull() ?: 0
            file.getAttribute("href").takeIf { executed > 0 && it.isNotEmpty() }?.removeSuffix(".xml")
        }

    /** The project root those sources are relative to, as the interpreter saw it. */
    fun coverageRoot(coverageXml: Path): String? =
        readXmlRoot(coverageXml.resolve("index.xml")).childElements("project").firstOrNull()
            ?.getAttribute("source")?.takeIf { it.isNotEmpty() }

    /** Infection finds `index.xml` and `*junit.xml` anywhere under `--coverage`, so it gets a directory with just those. */
    fun assemble(ready: TestoMutationReadiness.Ready, target: Path) {
        NioFiles.deleteRecursively(target)
        Files.createDirectories(target)
        FileUtil.copyDir(ready.coverageXml.toFile(), target.resolve("coverage-xml").toFile())
        dropUnreadable(target.resolve("coverage-xml"))
        Files.copy(ready.junit, target.resolve("junit.xml"))
    }

    /**
     * Takes out of `index.xml` every file Infection could not read: it stops at the first. Code run through eval() is
     * one — PHP reports it as a file of its own, `Foo.php(64) : eval()'d code`, which is no source and, on Windows, no
     * path either — as is any file whose report is not beside the index.
     */
    fun dropUnreadable(coverageXml: Path) {
        val index = coverageXml.resolve("index.xml")
        val root = readXmlRoot(index)
        val dropped = root.descendants("file").filter { file ->
            val href = file.getAttribute("href")
            isEvaluated(file) || href.isEmpty() || runCatching { !Files.isRegularFile(coverageXml.resolve(href)) }.getOrDefault(true)
        }
        if (dropped.isEmpty()) return
        dropped.forEach { it.parentNode.removeChild(it) }
        Files.newOutputStream(index).use { out ->
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(root.ownerDocument), StreamResult(out))
        }
    }

    private val EVALUATED = Regex("""\(\d+\) : eval\(\)'d code$""")

    private fun isEvaluated(file: Element): Boolean = EVALUATED.containsMatchIn(file.getAttribute("name"))
}

/** What the user has to do to get a run mutation testing can start from. */
internal val TestoMutationReadiness.Missing.hint: String
    get() = when (this) {
        TestoMutationReadiness.Missing.NOT_FINISHED -> TestoBundle.message("infection.missing.notFinished")
        TestoMutationReadiness.Missing.NOT_PASSED -> TestoBundle.message("infection.missing.notPassed")
        TestoMutationReadiness.Missing.NO_COVERAGE_XML, TestoMutationReadiness.Missing.NO_JUNIT -> TestoBundle.message("infection.missing.reports")
    }
