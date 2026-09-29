package com.github.xepozz.testo.infection

import com.github.xepozz.testo.runs.StoredReport
import com.github.xepozz.testo.runs.TestoRunManifest
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class TestoInfectionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `arguments skip the initial run over the given reports`() {
        assertEquals(
            listOf(
                "--coverage=/app/.idea/testo/staging/run",
                "--skip-initial-tests",
                "--test-framework=testo",
                "--teamcity",
                "--no-progress",
                "--no-interaction",
                "--filter=src/A.php,src/B.php",
                "--logger-html=/tmp/report.html",
                "--logger-text=/tmp/mutations.log",
                "--log-verbosity=all",
            ),
            TestoInfectionArguments.build(
                "/app/.idea/testo/staging/run",
                listOf("src/A.php", "src/B.php"),
                "/tmp/report.html",
                "/tmp/mutations.log",
            ),
        )
    }

    @Test
    fun `options map onto their flags, the extra ones last`() {
        val options = TestoInfectionOptions(
            scope = TestoRunnerSettings.INFECTION_SCOPE_GIT_LINES,
            gitDiffBase = "origin/main",
            threads = "max",
            onlyCoveringTestCases = true,
            withUncovered = true,
            timeoutsAsEscaped = true,
            mutators = "@default,-MethodCallRemoval",
            extra = "--min-msi=80 --debug",
        )

        assertEquals(
            listOf(
                "--coverage=/c",
                "--skip-initial-tests",
                "--test-framework=testo",
                "--teamcity",
                "--no-progress",
                "--no-interaction",
                "--git-diff-lines",
                "--git-diff-base=origin/main",
                "--threads=max",
                "--only-covering-test-cases",
                "--with-uncovered",
                "--with-timeouts",
                "--mutators=@default,-MethodCallRemoval",
                "--min-msi=80",
                "--debug",
            ),
            TestoInfectionArguments.build("/c", listOf("src/A.php"), null, null, options),
        )
        assertEquals(
            listOf("--coverage=/c", "--skip-initial-tests", "--test-framework=testo", "--teamcity", "--no-progress", "--no-interaction"),
            TestoInfectionArguments.build("/c", listOf("src/A.php"), null, null, TestoInfectionOptions(scope = TestoRunnerSettings.INFECTION_SCOPE_ALL)),
        )
    }

    @Test
    fun `no filter when there are no sources or too many`() {
        assertNull(TestoInfectionArguments.filter(emptyList()))
        val many = List(1_000) { "src/Some/Long/Directory/Name/File$it.php" }
        assertNull(TestoInfectionArguments.filter(many))
    }

    @Test
    fun `infection is looked for next to testo, then under the project`() {
        assertEquals(
            listOf(
                "D:/p/vendor/bin/infection",
                "D:/p/tools/infection/vendor/bin/infection",
                "D:/p/infection.phar",
                "D:/p/tools/infection.phar",
            ),
            TestoInfectionExecutable.candidates("D:\\p\\vendor\\bin\\testo", "D:\\p\\"),
        )
        assertEquals(
            "/app/tools/infection/vendor/bin/infection",
            TestoInfectionExecutable.find("/app/vendor/bin/testo", "/app") { it.startsWith("/app/tools") },
        )
    }

    @Test
    fun `readiness wants a finished passing run with coverage-xml and junit`() {
        val runDir = temp.newFolder("run").toPath()
        Files.createDirectories(runDir.resolve("reports/coverage-xml"))
        Files.writeString(runDir.resolve("reports/coverage-xml/index.xml"), "<phpunit/>")
        Files.writeString(runDir.resolve("reports/junit.xml"), "<testsuites/>")
        val coverage = StoredReport("coverage-xml", stored = "reports/coverage-xml/index.xml")
        val junit = StoredReport("junit", stored = "reports/junit.xml")

        assertEquals(TestoMutationReadiness.Missing.NOT_FINISHED, TestoInfectionReports.readiness(runDir, null))
        assertEquals(
            TestoMutationReadiness.Missing.NOT_PASSED,
            TestoInfectionReports.readiness(runDir, TestoRunManifest(exitCode = 1, reports = listOf(coverage, junit))),
        )
        assertEquals(
            TestoMutationReadiness.Missing.NO_JUNIT,
            TestoInfectionReports.readiness(runDir, TestoRunManifest(exitCode = 0, reports = listOf(coverage))),
        )
        assertEquals(
            TestoMutationReadiness.Missing.NO_COVERAGE_XML,
            TestoInfectionReports.readiness(runDir, TestoRunManifest(exitCode = 0, reports = listOf(junit))),
        )
        assertEquals(
            TestoMutationReadiness.Ready(runDir.resolve("reports/coverage-xml"), runDir.resolve("reports/junit.xml")),
            TestoInfectionReports.readiness(runDir, TestoRunManifest(exitCode = 0, reports = listOf(coverage, junit))),
        )
    }

    @Test
    fun `covered sources are the index entries with an executed line`() {
        val dir = temp.newFolder("coverage-xml").toPath()
        Files.writeString(
            dir.resolve("index.xml"),
            """
            <phpunit xmlns="https://schema.phpunit.de/coverage/1.0">
              <project source="/app">
                <directory name="/app">
                  <file name="A.php" href="src/A.php.xml"><totals><lines total="4" executed="2"/></totals></file>
                  <file name="B.php" href="src/B.php.xml"><totals><lines total="3" executed="0"/></totals></file>
                  <directory name="Sub">
                    <file name="C.php" href="src/Sub/C.php.xml"><totals><lines total="1" executed="1"/></totals></file>
                  </directory>
                </directory>
              </project>
            </phpunit>
            """.trimIndent(),
        )

        assertEquals(listOf("src/A.php", "src/Sub/C.php"), TestoInfectionReports.coveredSourceFiles(dir))
    }

    @Test
    fun `assembled coverage holds exactly the two reports`() {
        val source = temp.newFolder("source").toPath()
        Files.createDirectories(source.resolve("coverage-xml/src"))
        Files.writeString(source.resolve("coverage-xml/index.xml"), "<phpunit/>")
        Files.writeString(source.resolve("coverage-xml/src/A.php.xml"), "<phpunit/>")
        Files.writeString(source.resolve("junit.xml"), "<testsuites/>")
        val target = temp.root.toPath().resolve("assembled")

        TestoInfectionReports.assemble(
            TestoMutationReadiness.Ready(source.resolve("coverage-xml"), source.resolve("junit.xml")),
            target,
        )

        assertEquals(
            listOf("coverage-xml/index.xml", "coverage-xml/src/A.php.xml", "junit.xml"),
            Files.walk(target).use { paths ->
                paths.filter(Files::isRegularFile).map { target.relativize(it).toString().replace('\\', '/') }.sorted().toList()
            },
        )
    }

    @Test
    fun `locations point at the mutated offset or the file`() {
        assertEquals(
            InfectionLocation("D:\\p\\src\\A.php", 1335, 1339),
            parseInfectionLocation("infection://D:\\p\\src\\A.php::1335-1339"),
        )
        assertEquals(InfectionLocation("/app/src/A.php", null, null), parseInfectionLocation("file:///app/src/A.php"))
        assertNull(parseInfectionLocation("infection://no-range"))
        assertNull(parseInfectionLocation("php_qn:///app/src/A.php"))
    }

}
