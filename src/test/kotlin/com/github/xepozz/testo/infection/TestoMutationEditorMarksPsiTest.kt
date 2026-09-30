package com.github.xepozz.testo.infection

import com.intellij.openapi.editor.markup.GutterIconRenderer
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
}
