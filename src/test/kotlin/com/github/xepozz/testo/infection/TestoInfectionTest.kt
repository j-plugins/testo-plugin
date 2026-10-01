package com.github.xepozz.testo.infection

import com.github.xepozz.testo.runs.StoredReport
import com.github.xepozz.testo.runs.TestoRunManifest
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the covered files are filtered only when uncovered code is mutated too`() {
        assertEquals(
            listOf("--with-uncovered", "--filter=src/A.php,src/B.php"),
            TestoInfectionArguments.build("/c", listOf("src/A.php", "src/B.php"), null, null, TestoInfectionOptions(withUncovered = true))
                .filter { it.startsWith("--with-uncovered") || it.startsWith("--filter") }
                .sortedDescending(),
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
            staticAnalysisTool = "phpstan",
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
                "--static-analysis-tool=phpstan",
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
    fun `a rerun of one mutant names it and its file, whatever the scope`() {
        val options = TestoInfectionOptions(
            scope = TestoRunnerSettings.INFECTION_SCOPE_GIT_LINES,
            mutantId = "7fef23da",
            mutantFile = "src/A.php",
        )

        assertEquals(
            listOf("--filter=src/A.php", "--id=7fef23da"),
            TestoInfectionArguments.build("/c", listOf("src/A.php", "src/B.php"), null, null, options)
                .filter { it.startsWith("--filter") || it.startsWith("--id") || it.startsWith("--git-diff") },
        )
    }

    @Test
    fun `a mutated file is matched to the coverage's own spelling of it`() {
        val sources = listOf("src/A.php", "lib/src/A.php", "src/B.php")

        assertEquals("lib/src/A.php", sourceOf("/app/lib/src/A.php", sources))
        assertEquals("src/A.php", sourceOf("D:\\p\\src\\A.php", sources))
        assertNull(sourceOf("/app/src/C.php", sources))
    }

    @Test
    fun `a file or directory picked on the host becomes the filter the coverage spells`() {
        val sources = listOf("src/A.php", "src/Sub/C.php", "lib/B.php")
        val covered = listOf("D:/p/src/A.php", "D:/p/src/Sub/C.php", "D:/p/lib/B.php")

        assertEquals("src/Sub/C.php", mutationFilterFor("D:\\p\\src\\Sub\\C.php", false, covered, sources))
        assertEquals("src/Sub/", mutationFilterFor("D:/p/src/Sub", true, covered, sources))
        assertEquals("src/", mutationFilterFor("D:/p/src/", true, covered, sources))
        assertEquals("", mutationFilterFor("D:/p", true, covered, sources))
        assertNull(mutationFilterFor("D:/p/tests", true, covered, sources))
        assertNull(mutationFilterFor("D:/p/src/D.php", false, covered, sources))
    }

    @Test
    fun `a narrowed run filters by its file or directory whatever the scope`() {
        val options = TestoInfectionOptions(scope = TestoRunnerSettings.INFECTION_SCOPE_GIT_LINES, filter = "#/app/src/Sub/#")

        assertEquals(
            listOf("--filter=#/app/src/Sub/#"),
            TestoInfectionArguments.build("/c", listOf("src/A.php"), null, null, options)
                .filter { it.startsWith("--filter") || it.startsWith("--git-diff") },
        )
    }

    @Test
    fun `a narrowed filter names its path under the coverage root and keeps its commas`() {
        assertEquals("#/app/src/Sub/#", TestoInfectionArguments.pathFilter("/app/", "src/Sub/"))
        assertEquals("#D\\:/p/src/Sub/C\\.php#", TestoInfectionArguments.pathFilter("D:\\p", "src/Sub/C.php"))
        assertEquals("#/src/a\\x2Cb\\-c/\\#1/#", TestoInfectionArguments.pathFilter(null, "src/a,b-c/#1/"))
        assertEquals("#C\\:/p/src/A\\.php#", TestoInfectionArguments.pathFilter("C:\\p\\src\\A.php"))
    }

    @Test
    fun `the report's Back link to the drive root is blanked out in place`() {
        val body = "<html>\n    <body>\n        <a href=\"/\">Back</a>\n        <mutation-test-report-app></mutation-test-report-app>\n"
        val report = temp.newFile("report.html").toPath()
        Files.writeString(report, body + "x".repeat(10_000))

        TestoInfectionHtmlReport.clean(report)

        val cleaned = Files.readString(report)
        assertEquals(body.length + 10_000, cleaned.length)
        assertEquals(body.replace("<a href=\"/\">Back</a>", " ".repeat(20)), cleaned.take(body.length))
        assertEquals(-1, TestoInfectionHtmlReport.backLinkOffset("<a href=\"/docs\">Back</a>".toByteArray()))
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
    fun `code run through eval and files without a report are left out of the index Infection reads`() {
        val source = temp.newFolder("eval").toPath()
        Files.createDirectories(source.resolve("coverage-xml/src"))
        Files.writeString(
            source.resolve("coverage-xml/index.xml"),
            """
            <phpunit xmlns="https://schema.phpunit.de/coverage/1.0">
              <project source="/app">
                <directory name="/app">
                  <file name="A.php" href="src/A.php.xml"><totals><lines total="4" executed="2"/></totals></file>
                  <file name="A.php(64) : eval()'d code" href="src/A.php(64) : eval()'d code.xml"><totals><lines total="1" executed="1"/></totals></file>
                  <file name="Gone.php" href="src/Gone.php.xml"><totals><lines total="1" executed="1"/></totals></file>
                </directory>
              </project>
            </phpunit>
            """.trimIndent(),
        )
        Files.writeString(source.resolve("coverage-xml/src/A.php.xml"), "<phpunit/>")
        Files.writeString(source.resolve("junit.xml"), "<testsuites/>")
        val target = temp.root.toPath().resolve("assembled-eval")

        assertEquals(listOf("src/A.php", "src/Gone.php"), TestoInfectionReports.coveredSourceFiles(source.resolve("coverage-xml")))
        TestoInfectionReports.assemble(
            TestoMutationReadiness.Ready(source.resolve("coverage-xml"), source.resolve("junit.xml")),
            target,
        )

        assertEquals(listOf("src/A.php"), TestoInfectionReports.coveredSourceFiles(target.resolve("coverage-xml")))
        assertTrue(Files.readString(target.resolve("coverage-xml/index.xml")).contains("xmlns=\"https://schema.phpunit.de/coverage/1.0\""))
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

    @Test
    fun `a rerun takes a whole file in one process and every other mutant in one of its own`() {
        val whole = MutatedFile("f1", "A.php", "/app/src/A.php")
        val partial = MutatedFile("f2", "B.php", "/app/src/B.php")
        val single = MutatedFile("f3", "C.php", "/app/src/C.php")
        fun MutatedFile.mutant(id: String) = Mutant(id, this, "Plus", id, null, null).also { mutants += it }
        val a = listOf(whole.mutant("a1"), whole.mutant("a2"))
        val b = listOf(partial.mutant("b1"), partial.mutant("b2"), partial.mutant("b3"))
        val c = single.mutant("c1")
        val targets = a + b.take(2) + c

        fun describe(units: List<RerunUnit>) = units.map {
            when (it) {
                is RerunUnit.One -> it.mutant.hash
                is RerunUnit.File -> it.file.path
            }
        }

        assertEquals(listOf("/app/src/A.php", "b1", "b2", "c1"), describe(rerunUnits(targets, true)))
        assertEquals(listOf("a1", "a2", "b1", "b2", "c1"), describe(rerunUnits(targets, false)))
    }
}
