package com.github.xepozz.testo.infection

import com.intellij.openapi.editor.Document
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class TestoMutationIgnorePsiTest : BasePlatformTestCase() {
    fun testTheAnnotationGoesAboveTheMutantsStatementOnce() {
        val code = "<?php\n\nfunction f(\$a)\n{\n    if (\$a) {\n        return \$a > 1;\n    }\n}\n"
        val (run, mutant, document) = mutantIn(code, line = 6, original = "        return \$a > 1;\n    }\n}\n")

        assertTrue(TestoMutationIgnore.canIgnore(project, run, mutant))
        assertTrue(TestoMutationIgnore.ignore(project, run, mutant))

        assertEquals(code.replace("        return", "        // @infection-ignore-all\n        return"), document.text)
    }

    fun testAnAlreadyIgnoredStatementIsNotAnnotatedAgain() {
        val code = "<?php\n\nclass A\n{\n    // @infection-ignore-all\n    public function f(\$a) { return \$a > 1; }\n}\n"
        val (run, mutant) = mutantIn(code, line = 6, original = "    public function f(\$a) { return \$a > 1; }\n}\n")

        assertFalse(TestoMutationIgnore.canIgnore(project, run, mutant))
    }

    fun testAMemberSharingItsLineWithTheMutationTakesTheAnnotation() {
        val code = "<?php\n\nclass A\n{\n    public function f(\$a) { return \$a > 1; }\n}\n"
        val (run, mutant, document) = mutantIn(code, line = 5, original = "    public function f(\$a) { return \$a > 1; }\n}\n")

        assertTrue(TestoMutationIgnore.ignore(project, run, mutant))

        assertEquals(code.replace("    public", "    // @infection-ignore-all\n    public"), document.text)
    }

    fun testUndoRestoresTheOriginalSource() {
        // Caret restoration can be a separate platform undo step; this test checks the document command itself.
        com.intellij.openapi.util.registry.Registry.get("ide.undo.transparent.caret.movement").setValue(true, testRootDisposable)
        val code = "<?php\nfunction f(\$a) {\n return \$a > 1;\n}\n"
        val (run, mutant, document) = mutantIn(code, 3, " return \$a > 1;\n}\n")
        assertTrue(TestoMutationIgnore.ignore(project, run, mutant))
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(editor))
        val action = undo.getUndoActionNameAndDescription(editor)
        undo.undo(editor)
        assertEquals(action.toString(), code, document.text)
    }

    fun testChangedSnippetsAndFilesOutsideTheProjectCannotBeEdited() {
        val code = "<?php\nfunction f(\$a) {\n return \$a > 1;\n}\n"
        val (run, mutant, document) = mutantIn(code, 3, " return \$a > 1;\n}\n", inProject = false)
        assertFalse(TestoMutationIgnore.canIgnore(project, run, mutant))
        assertFalse(TestoMutationIgnore.ignore(project, run, mutant))
        assertFalse(TestoMutationApply.canApply(project, run, mutant))
        assertFalse(TestoMutationApply.apply(project, run, mutant))
        assertEquals(code, document.text)
        val (ownRun, ownMutant, ownDocument) = mutantIn(code, 3, " return \$a > 1;\n}\n")
        WriteCommandAction.runWriteCommandAction(project) { ownDocument.setText(code.replace("> 1", "> 2")) }
        assertFalse(TestoMutationIgnore.canIgnore(project, ownRun, ownMutant))
        assertFalse(TestoMutationApply.apply(project, ownRun, ownMutant))
        assertEquals(code.replace("> 1", "> 2"), ownDocument.text)
    }

    private fun mutantIn(code: String, line: Int, original: String, inProject: Boolean = true): Triple<TestoMutationRun, Mutant, Document> {
        val dir = Files.createTempDirectory("testo-mutation")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        val source = dir.resolve("A.php")
        Files.writeString(source, code)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
        if (inProject) com.intellij.testFramework.PsiTestUtil.addContentRoot(module, virtual.parent)
        myFixture.configureFromExistingVirtualFile(virtual)
        val document = FileDocumentManager.getInstance().getDocument(virtual)!!

        val run = TestoMutationRun("t", dir, dir) { it }
        val file = MutatedFile("f1", "A.php", source.toString())
        val mutant = Mutant("m1", file, "Infection\\Mutator\\ConditionalBoundary\\GreaterThan", "h1", null, null).apply {
            status = MutantStatus.ESCAPED
            finished = true
            this.original = original
            mutated = original.replace("\$a > 1", "\$a >= 1")
            firstLine = line
            lines = line..line
        }
        file.mutants += mutant
        run.files += file
        return Triple(run, mutant, document)
    }
}
