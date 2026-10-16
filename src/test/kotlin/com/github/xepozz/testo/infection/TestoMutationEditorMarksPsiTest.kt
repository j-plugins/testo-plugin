package com.github.xepozz.testo.infection

import com.github.xepozz.testo.runs.RunRetention
import com.github.xepozz.testo.runs.TestoRunManifest
import com.github.xepozz.testo.runs.TestoRunStore
import com.github.xepozz.testo.runs.writeManifestFile
import com.google.gson.Gson
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class TestoMutationEditorMarksPsiTest : BasePlatformTestCase() {
    fun testMutantsAreMarkedOnTheirLinesAndEscapedCodeIsUnderlined() {
        val dir = Files.createTempDirectory("testo-mutation")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        val source = dir.resolve("A.php")
        val code = "<?php\n\nreturn \$a > 1;\n"
        Files.writeString(source, code)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
        myFixture.openFileInEditor(virtual)

        val run = TestoMutationRun("t", dir, dir) { it }
        val file = MutatedFile("f1", "A.php", source.toString())
        val at = code.indexOf("\$a > 1")
        file.mutants += Mutant("m1", file, "Infection\\Mutator\\ConditionalBoundary\\GreaterThan", "h1", at, at + 5)
            .apply { status = MutantStatus.ESCAPED; finished = true }
        file.mutants += Mutant("m2", file, "Infection\\Mutator\\Number\\DecrementInteger", "h2", at + 5, at + 5)
            .apply { status = MutantStatus.KILLED; finished = true }
        run.files += file
        run.fingerprints[file.path] = fingerprintOf(source)!!
        run.finish(0)
        TestoMutationService.getInstance(project).track(run)

        val editor = myFixture.editor
        val deadline = System.currentTimeMillis() + 5_000
        while (editor.markupModel.allHighlighters.none { it.gutterIconRenderer != null } && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }

        val gutters = editor.markupModel.allHighlighters.filter { it.gutterIconRenderer != null }
        assertEquals(1, gutters.size)
        assertEquals(2, editor.document.getLineNumber(gutters.single().startOffset))
        val tooltip = (gutters.single().gutterIconRenderer as GutterIconRenderer).tooltipText.orEmpty()
        assertTrue(tooltip, tooltip.indexOf("GreaterThan") < tooltip.indexOf("DecrementInteger"))

        val underline = editor.markupModel.allHighlighters.single { it.gutterIconRenderer == null && it.endOffset > it.startOffset }
        assertEquals("\$a > 1", editor.document.text.substring(underline.startOffset, underline.endOffset))

        val marks = TestoMutationEditorMarks.getInstance(project)
        marks.hiddenStatuses = setOf(MutantStatus.ESCAPED)
        try {
            val hiddenBy = System.currentTimeMillis() + 5_000
            while (editor.markupModel.allHighlighters.any { it.endOffset > it.startOffset && it.gutterIconRenderer == null } &&
                System.currentTimeMillis() < hiddenBy
            ) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(50)
            }
            assertTrue(editor.markupModel.allHighlighters.none { it.endOffset > it.startOffset && it.gutterIconRenderer == null })
            val tooltip2 = (editor.markupModel.allHighlighters.single { it.gutterIconRenderer != null }.gutterIconRenderer as GutterIconRenderer).tooltipText.orEmpty()
            assertFalse(tooltip2, tooltip2.contains("GreaterThan"))
        } finally {
            marks.hiddenStatuses = emptySet()
        }
    }

    fun testClearingTheHistoryTakesTheMarksOfADeletedRunAway() {
        val dir = Files.createTempDirectory("testo-mutation")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        val source = dir.resolve("A.php")
        val code = "<?php\n\nreturn \$a > 1;\n"
        Files.writeString(source, code)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
        myFixture.openFileInEditor(virtual)

        // An old timestamp in the name: past the grace a run without a manifest is left alone for.
        val runDir = Files.createDirectories(TestoRunStore.getInstance(project).root().resolve("1000-Old run"))
        val run = TestoMutationRun("t", runDir, runDir.resolve("infection")) { it }
        val file = MutatedFile("f1", "A.php", source.toString())
        val at = code.indexOf("\$a > 1")
        file.mutants += Mutant("m1", file, "Infection\\Mutator\\ConditionalBoundary\\GreaterThan", "h1", at, at + 5)
            .apply { status = MutantStatus.ESCAPED; finished = true }
        run.files += file
        run.fingerprints[file.path] = fingerprintOf(source)!!
        run.finish(0)
        val service = TestoMutationService.getInstance(project)
        service.track(run)
        try {
            val editor = myFixture.editor
            fun gutters() = editor.markupModel.allHighlighters.filter { it.gutterIconRenderer != null }
            val shown = System.currentTimeMillis() + 5_000
            while (gutters().isEmpty() && System.currentTimeMillis() < shown) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(50)
            }
            assertEquals(1, gutters().size)

            TestoRunStore.getInstance(project).clearHistory(keepLocked = false, spare = null)

            assertFalse(Files.exists(runDir))
            assertNotSame(run, service.current())
            val cleared = System.currentTimeMillis() + 5_000
            while (gutters().isNotEmpty() && System.currentTimeMillis() < cleared) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(50)
            }
            assertEmpty(gutters())
        } finally {
            NioFiles.deleteRecursively(runDir)
            service.forget(listOf(runDir))
        }
    }

    fun testClearingTheHistoryKeepsARunBeingMutated() {
        val store = TestoRunStore.getInstance(project)
        val runDir = Files.createDirectories(store.root().resolve("1000-Busy run"))
        writeManifestFile(runDir, TestoRunManifest(configurationName = "Busy run", startedAt = 1000), Gson())
        val run = TestoMutationRun("t", runDir, runDir.resolve("infection")) { it }
        val service = TestoMutationService.getInstance(project)
        service.track(run)
        try {
            store.clearHistory(keepLocked = false, spare = null)

            assertTrue(Files.isDirectory(runDir))
            assertSame(run, service.runFor(runDir))
            assertEquals(RunRetention.DISCARD, store.retentionOf(runDir))
        } finally {
            run.finish(null)
            NioFiles.deleteRecursively(runDir)
            service.forget(listOf(runDir))
        }
    }
}
