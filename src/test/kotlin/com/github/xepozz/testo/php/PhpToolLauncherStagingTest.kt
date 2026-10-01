package com.github.xepozz.testo.php

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class PhpToolLauncherStagingTest {
    private val base = Files.createTempDirectory("testo-staging")
    private val project = base.resolve("project")
    private val app = project.resolve("app")
    private val coverage = Files.createDirectories(base.resolve("system/coverage")).also {
        Files.writeString(it.resolve("index.xml"), "<phpunit/>")
    }
    private val roots = PhpToolLauncher.stagingRoots(project.toString(), app.toString())

    @Test
    fun `the copy goes to the project's idea when the interpreter sees it`() {
        val shared = PhpToolLauncher.stage(coverage, "run", roots) { mapUnder(project, "/opt/project", it) }!!

        assertEquals("/opt/project/.idea/testo/staging/run", shared.path)
        assertTrue(Files.isRegularFile(project.resolve(".idea/testo/staging/run/index.xml")))
    }

    @Test
    fun `a container mounting only part of the project gets the copy in the working directory`() {
        val shared = PhpToolLauncher.stage(coverage, "run", roots) { mapUnder(app, "/var/www", it) }!!

        assertEquals("/var/www/.testo-staging/run", shared.path)
        assertTrue(Files.isRegularFile(app.resolve(".testo-staging/run/index.xml")))
        assertEquals("*\n", Files.readString(app.resolve(".testo-staging/.gitignore")))
        assertFalse(Files.exists(project.resolve(".idea")))
    }

    @Test
    fun `nothing is written when no root is visible`() {
        assertNull(PhpToolLauncher.stage(coverage, "run", roots) { null })
        assertFalse(Files.exists(project))
    }

    @Test
    fun `releasing the last copy takes the working directory's staging root with it`() {
        val shared = PhpToolLauncher.stage(coverage, "run", roots) { mapUnder(app, "/var/www", it) }!!

        shared.release()

        assertFalse(Files.exists(app.resolve(".testo-staging")))
    }

    @Test
    fun `releasing one copy keeps a parallel run's`() {
        val first = PhpToolLauncher.stage(coverage, "first", roots) { mapUnder(app, "/var/www", it) }!!
        PhpToolLauncher.stage(coverage, "second", roots) { mapUnder(app, "/var/www", it) }!!

        first.release()

        assertFalse(Files.exists(app.resolve(".testo-staging/first")))
        assertTrue(Files.isRegularFile(app.resolve(".testo-staging/second/index.xml")))
    }

    private fun mapUnder(local: Path, remote: String, path: String): String? =
        Path.of(path).takeIf { it.startsWith(local) }?.let { remote + "/" + local.relativize(it).toString().replace('\\', '/') }
}
