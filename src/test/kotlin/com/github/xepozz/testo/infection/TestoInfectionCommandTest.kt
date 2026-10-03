package com.github.xepozz.testo.infection

import com.github.xepozz.testo.php.TestoPreparedTool
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.php.TestoToolRequest
import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.io.NioFiles
import com.intellij.testFramework.LightPlatformTestCase
import org.junit.Assert.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class TestoInfectionCommandTest : LightPlatformTestCase() {
    private lateinit var root: Path

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("testo-command-")
    }

    override fun tearDown() {
        try { NioFiles.deleteRecursively(root) } finally { super.tearDown() }
    }

    private fun launch(withHtml: Boolean = true): TestoInfectionLaunch {
        val coverage = Files.createDirectories(root.resolve("reports/coverage-xml/src"))
        Files.writeString(coverage.resolve("A.php.xml"), "<file/>")
        Files.writeString(coverage.parent.resolve("index.xml"), "<coverage><project><file href=\"src/A.php.xml\"/></project></coverage>")
        val junit = Files.writeString(root.resolve("reports/junit.xml"), "<testsuites/>")
        return TestoInfectionLaunch(TestoMutationReadiness.Ready(coverage.parent, junit), listOf("src/A.php"), root.resolve("mutation"), withHtml = withHtml)
    }

    private inner class Environment(private val remote: Boolean = false) : TestoToolEnvironment {
        override val testoExecutable = if (remote) "/app/vendor/bin/testo" else root.resolve("vendor/bin/testo").toString()
        override val workingDirectory = root.toString()
        var request: TestoToolRequest? = null
        override fun toLocal(path: String): String? = path.takeUnless { remote }
        override fun prepare(request: TestoToolRequest): TestoPreparedTool {
            this.request = request
            return TestoPreparedTool("infection", { error("The preparation must not start a process") })
        }
    }

    fun testMissingBinaryFailsBeforePreparingOrCopyingReports() {
        val environment = Environment()
        val launch = launch()
        assertThrows(ExecutionException::class.java) { TestoInfectionCommand.create(environment, launch) }
        assertNull(environment.request)
        assertFalse(Files.exists(launch.workDir))
    }

    fun testPreparationPassesFullReportTreeAndReachableOutputs() {
        val environment = Environment()
        val binary = root.resolve("vendor/bin/infection")
        Files.createDirectories(binary.parent)
        Files.writeString(binary, "")
        val launch = launch()
        TestoInfectionCommand.create(environment, launch).use {
            val request = environment.request!!
            assertEquals(binary.toString(), request.script)
            assertEquals("<file/>", Files.readString(request.inputDirectory.resolve("coverage-xml/src/A.php.xml")))
            assertTrue(Files.isRegularFile(request.inputDirectory.resolve("junit.xml")))
            assertEquals(listOf(launch.htmlReport, launch.textLog), request.outputFiles)
            val args = request.arguments("/app/reports", mapOf(launch.textLog to "/app/mutations.log"))
            assertTrue(args.contains("--coverage=/app/reports"))
            assertTrue(args.contains("--logger-text=/app/mutations.log"))
            assertFalse(args.any { it.startsWith("--logger-html=") })
        }
        assertTrue(Files.isRegularFile(launch.ready.coverageXml.resolve("src/A.php.xml")))
    }

    fun testUnmappedRemoteBinaryKeepsItsSiblingAndRerunDoesNotOverwriteHtml() {
        val environment = Environment(remote = true)
        val launch = launch(withHtml = false)
        Files.createDirectories(launch.workDir)
        Files.writeString(launch.htmlReport, "full report")
        TestoInfectionCommand.create(environment, launch).use {
            assertEquals("/app/vendor/bin/infection", environment.request!!.script)
            assertEquals(listOf(launch.textLog), environment.request!!.outputFiles)
        }
        assertEquals("full report", Files.readString(launch.htmlReport))
    }
}
