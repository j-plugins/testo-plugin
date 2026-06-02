package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.PhpBacktraceFileFilter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class PhpBacktraceFileFilterTest : BasePlatformTestCase() {

    private var tempDir: File? = null

    override fun tearDown() {
        try {
            tempDir?.let { FileUtil.delete(it) }
        } finally {
            super.tearDown()
        }
    }

    // The production PhpBacktraceFileFilter resolves paths through LocalFileSystem, so the file a
    // backtrace points to must exist on the real local disk. The default light fixture's
    // `addFileToProject` writes into an in-memory `temp://` VFS whose paths LocalFileSystem can't
    // resolve, so we create a genuine file on disk and refresh it into LocalFileSystem instead.
    private fun realFilePath(): String {
        val dir = tempDir ?: FileUtil.createTempDirectory("testo-backtrace", null).also { tempDir = it }
        val ioFile = File(dir, "Foo.php")
        ioFile.writeText("<?php\n\n\n\n")
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
        assertNotNull("Failed to create a real on-disk file for the test", vFile)
        return vFile!!.path
    }

    private fun assertLinksTo(line: String, target: String) {
        val result = PhpBacktraceFileFilter(project).applyFilter(line, line.length)
        assertNotNull("Expected a non-null Result for: $line", result)

        val items = result!!.resultItems
        assertEquals("Expected exactly one ResultItem for: $line", 1, items.size)

        val item = items.first()
        val expectedStart = line.indexOf(target)
        val expectedEnd = expectedStart + target.length
        assertEquals("Highlight start should cover `$target`", expectedStart, item.highlightStartOffset)
        assertEquals("Highlight end should cover `$target`", expectedEnd, item.highlightEndOffset)

        assertTrue(
            "Hyperlink should be an OpenFileHyperlinkInfo",
            item.hyperlinkInfo is OpenFileHyperlinkInfo
        )
    }

    // Form 1: `#0 /abs/File.php(48): Class::method()` (parens)
    fun testLinksParenthesisedFrameToFile() {
        val path = realFilePath()
        val target = "$path(3)"
        val line = "#0 $target: X\\Y::z()\n"
        assertLinksTo(line, target)
    }

    // Form 2: `/abs/File.php:48` (colon)
    fun testLinksColonLocationToFile() {
        val path = realFilePath()
        val target = "$path:3"
        val line = "  at $target\n"
        assertLinksTo(line, target)
    }

    // Form 3: `... in /abs/File.php on line 48` (PHP error)
    fun testLinksPhpErrorOnLineToFile() {
        val path = realFilePath()
        val target = "$path on line 3"
        val line = "PHP Fatal error:  Uncaught Error in $target\n"
        assertLinksTo(line, target)
    }

    fun testReturnsNullForLineWithoutPath() {
        val line = "Some plain output with no frame\n"
        val result = PhpBacktraceFileFilter(project).applyFilter(line, line.length)
        assertNull("Expected null when there is no linkable path pattern", result)
    }

    fun testReturnsNullWhenFileDoesNotExist() {
        val line = "#0 /no/such/path/Missing.php(42): X\\Y::z()\n"
        val result = PhpBacktraceFileFilter(project).applyFilter(line, line.length)
        assertNull("Expected null when the referenced file does not exist on disk", result)
    }
}
