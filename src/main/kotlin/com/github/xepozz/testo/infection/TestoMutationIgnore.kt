package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.php.TestoStatementTarget
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiFile

/**
 * Keeps Infection off a mutant's statement with `// @infection-ignore-all` above it. The annotation is the only one
 * Infection reads and covers every mutator of the node it precedes; ignoring by mutator lives in whichever
 * infection.json5 the project uses, which the plugin does not edit.
 */
internal object TestoMutationIgnore {
    const val ANNOTATION = "@infection-ignore-all"

    /** Where the file still reads the mutant as Infection saw it and the statement is not ignored yet. Needs read access. */
    fun canIgnore(project: Project, run: TestoMutationRun, mutant: Mutant): Boolean {
        if (!TestoMutationApply.canApply(project, run, mutant)) return false
        val statement = statement(project, run, mutant) ?: return false
        return !isAnnotated(statement)
    }

    fun ignore(project: Project, run: TestoMutationRun, mutant: Mutant): Boolean {
        if (!canIgnore(project, run, mutant)) return false
        val statement = statement(project, run, mutant) ?: return false
        val file = statement.anchor.containingFile.virtualFile ?: return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val line = document.getLineNumber(statement.range.startOffset)
        val lineStart = document.getLineStartOffset(line)
        val indent = document.charsSequence.subSequence(lineStart, statement.range.startOffset)
        WriteCommandAction.runWriteCommandAction(project, TestoBundle.message("infection.ignore.command", mutant.mutator), null, {
            document.insertString(lineStart, "$indent// $ANNOTATION\n")
        })
        OpenFileDescriptor(project, file, line, indent.length).navigate(true)
        return true
    }

    private fun statement(project: Project, run: TestoMutationRun, mutant: Mutant): TestoStatementTarget? {
        val line = mutant.lines?.first ?: return null
        val file = run.localPath(mutant.file.path)?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (line < 1 || line > document.lineCount) return null
        val psi = psiFile(project, file, document) ?: return null
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        val text = document.charsSequence
        val first = (start until end).firstOrNull { !text[it].isWhitespace() } ?: return null
        return statementAt(psi.findElementAt(first), document)
    }

    private fun psiFile(project: Project, file: VirtualFile, document: Document): PsiFile? {
        if (!PsiDocumentManager.getInstance(project).isCommitted(document)) return null
        return PsiManager.getInstance(project).findFile(file)?.takeIf { TestoPhp.getInstance().isPhpFile(it) }
    }

    private fun isAnnotated(statement: TestoStatementTarget): Boolean =
        generateSequence(statement.anchor.prevSibling) { it.prevSibling }
            .takeWhile { it is PsiWhiteSpace || it is PsiComment }
            .any { it is PsiComment && ANNOTATION in it.text }
}

/**
 * The statement or class member holding [leaf] that opens a line of its own: what `// @infection-ignore-all` goes
 * above, so the comment is that node's and not a neighbour's on the same line.
 */
internal fun statementAt(leaf: PsiElement?, document: Document): TestoStatementTarget? =
    TestoPhp.getInstance().statementAt(leaf, document)
