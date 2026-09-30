package com.github.xepozz.testo.infection

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class TestoMutationPanelPsiTest : BasePlatformTestCase() {
    fun testAFileEditedSinceTheRunIsMarked() {
        val dir = Files.createTempDirectory("testo-mutation")
        val source = dir.resolve("A.php")
        Files.writeString(source, "<?php return 1;")
        val run = TestoMutationRun("t", dir, dir) { it }
        val file = MutatedFile("f1", "A.php", source.toString())
        run.files += file
        run.fingerprints[file.path] = fingerprintOf(source)!!
        run.finish(0)
        Files.writeString(source, "<?php return 2;")

        val panel = TestoMutationPanel(project, run)
        Disposer.register(testRootDisposable, panel)
        val deadline = System.currentTimeMillis() + 5_000
        while (!panel.isChanged(file) && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }

        assertTrue(panel.isChanged(file))
    }

    fun testAFileEditedWhileTheTabIsOpenIsMarked() {
        val dir = Files.createTempDirectory("testo-mutation")
        val source = dir.resolve("B.php")
        Files.writeString(source, "<?php return 1;")
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
        val run = TestoMutationRun("t", dir, dir) { it }
        val file = MutatedFile("f1", "B.php", source.toString())
        run.files += file
        run.fingerprints[file.path] = fingerprintOf(source)!!
        run.finish(0)
        val panel = TestoMutationPanel(project, run)
        Disposer.register(testRootDisposable, panel)
        waitFor { false }
        assertFalse(panel.isChanged(file))

        val document = FileDocumentManager.getInstance().getDocument(virtual)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, " ") }
        waitFor { panel.isChanged(file) }
        assertTrue("an unsaved edit", panel.isChanged(file))

        WriteCommandAction.runWriteCommandAction(project) { document.deleteString(0, 1) }
        FileDocumentManager.getInstance().saveDocument(document)
        waitFor { !panel.isChanged(file) }
        assertFalse("undone and saved", panel.isChanged(file))
    }

    fun testTheDiffFillsTheDetailsWhenTheOutputIsSwitchedOffBeforeAMutantIsSelected() {
        val dir = Files.createTempDirectory("testo-mutation")
        val run = TestoMutationRun("t", dir, dir) { it }
        val file = MutatedFile("f1", "C.php", dir.resolve("C.php").toString())
        val mutant = Mutant("m1", file, "Infection\\Mutator\\Number\\DecrementInteger", "h1", 0, 1).apply {
            status = MutantStatus.ESCAPED
            finished = true
            original = "<?php return 1;"
            mutated = "<?php return 0;"
        }
        file.mutants += mutant
        run.files += file
        run.finish(0)
        val panel = TestoMutationPanel(project, run)
        Disposer.register(testRootDisposable, panel)
        panel.setSize(400, 600)
        TestoMutationDetails.showOutput = false
        try {
            layOut(panel)
            panel.select(mutant)
            waitFor { panel.diff.component.isShowing && panel.diff.component.height > 0 }
            layOut(panel)

            assertFalse(panel.outputPane.isVisible)
            assertTrue(panel.diff.component.isVisible)
            assertTrue(panel.diff.component.height > 0)
            val heightAfterSelection = panel.diff.component.height
            repeat(5) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(100)
            }
            layOut(panel)
            assertEquals(heightAfterSelection, panel.diff.component.height)
            assertFalse(panel.outputPane.isVisible)
        } finally {
            TestoMutationDetails.showOutput = false
        }
    }

    fun testTheLogStaysHiddenAfterBothPartsWereOffWhileMutantsWereSelected() {
        val dir = Files.createTempDirectory("testo-mutation")
        val run = TestoMutationRun("t", dir, dir) { it }
        run.appendLog("$ php vendor/bin/infection")
        val file = MutatedFile("f1", "E.php", dir.resolve("E.php").toString())
        val mutants = (1..2).map { index ->
            Mutant("m$index", file, "Infection\\Mutator\\Number\\DecrementInteger", "h$index", 0, 1).apply {
                status = MutantStatus.ESCAPED
                finished = true
                original = "<?php return $index;"
                mutated = "<?php return 0;"
            }
        }
        file.mutants += mutants
        run.files += file
        run.finish(0)
        TestoMutationDetails.showOutput = true
        TestoMutationDetails.showDiff = true
        try {
            val panel = TestoMutationPanel(project, run)
            Disposer.register(testRootDisposable, panel)
            panel.setSize(400, 600)
            waitFor { false }
            assertTrue("the log first", outputText(panel).startsWith("$ php vendor/bin/infection"))

            TestoMutationDetails.showOutput = false
            TestoMutationDetails.showDiff = false
            mutants.forEach { mutant ->
                panel.select(mutant)
                waitFor { false }
            }
            TestoMutationDetails.showOutput = true
            TestoMutationDetails.showDiff = true
            waitFor { false }

            assertTrue(panel.diff.component.isVisible)
            assertTrue(panel.outputPane.isVisible)
            assertTrue("the selected mutant's details under its diff", outputText(panel).contains("h2"))
        } finally {
            TestoMutationDetails.showOutput = false
            TestoMutationDetails.showDiff = true
        }
    }

    private fun outputText(panel: TestoMutationPanel): String =
        (panel.outputPane.viewport.view as javax.swing.text.JTextComponent).text

    // Headless: nothing lays the panel out on its own.
    private fun layOut(component: java.awt.Component) {
        if (component is java.awt.Container) {
            component.doLayout()
            component.components.forEach(::layOut)
        }
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!done() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(50)
        }
    }
}
