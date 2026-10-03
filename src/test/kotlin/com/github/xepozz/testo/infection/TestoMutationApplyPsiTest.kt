package com.github.xepozz.testo.infection

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class TestoMutationApplyPsiTest : BasePlatformTestCase() {
    fun testAMutantIsAppliedWhereTheFileStillReadsItsSnippetAndRevertedBack() {
        val dir = Files.createTempDirectory("testo-mutation")
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
        val code = "<?php\n\nfunction f(\$a)\n{\n    return \$a > 1;\n}\n"
        val source = dir.resolve("A.php")
        Files.writeString(source, code)
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
        com.intellij.testFramework.PsiTestUtil.addContentRoot(module, virtual.parent)
        val document = FileDocumentManager.getInstance().getDocument(virtual)!!

        val run = TestoMutationRun("t", dir, dir) { it }
        val file = MutatedFile("f1", "A.php", source.toString())
        val mutant = Mutant("m1", file, "Infection\\Mutator\\ConditionalBoundary\\GreaterThan", "h1", null, null).apply {
            status = MutantStatus.ESCAPED
            finished = true
            original = "{\n    return \$a > 1;\n}\n"
            mutated = "{\n    return \$a >= 1;\n}\n"
            firstLine = 4
            lines = 5..5
        }
        file.mutants += mutant
        run.files += file

        assertTrue(TestoMutationApply.canApply(run, mutant))
        assertFalse(TestoMutationApply.isApplied(run, mutant))

        assertTrue(TestoMutationApply.apply(project, run, mutant))
        assertEquals(code.replace("\$a > 1", "\$a >= 1"), document.text)
        assertTrue(TestoMutationApply.isApplied(run, mutant))
        assertFalse(TestoMutationApply.canApply(run, mutant))

        assertTrue(TestoMutationApply.revert(project, run, mutant))
        assertEquals(code, document.text)
    }

    fun testASnippetIsFoundOnlyWhereTheFileReadsIt() {
        val text = "a\nb\nc"

        assertEquals(2, snippetRange(text, 2, "b\n")?.startOffset)
        assertEquals(4, snippetRange(text, 3, "c\n")?.startOffset)
        assertNull(snippetRange(text, 2, "x\n"))
        assertNull(snippetRange(text, 9, "b\n"))
    }
}
