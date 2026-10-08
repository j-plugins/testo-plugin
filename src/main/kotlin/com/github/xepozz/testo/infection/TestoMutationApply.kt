package com.github.xepozz.testo.infection

import com.github.xepozz.testo.TestoBundle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Writes a mutant into its file as an ordinary, undoable edit: the tests that let it escape can then be run against it
 * by hand. It goes in only where the file still reads the snippet Infection mutated, so an edit since the run, or a
 * mutant without its code, is refused rather than written somewhere else.
 */
internal object TestoMutationApply {
    /** Where the file reads the mutant's original snippet: it can be applied. Needs read access. */
    fun canApply(project: Project, run: TestoMutationRun, mutant: Mutant): Boolean =
        locate(project, run, mutant, applied = false) != null

    /** Where the file reads the mutated snippet instead: it is applied, and can be reverted. Needs read access. */
    fun isApplied(project: Project, run: TestoMutationRun, mutant: Mutant): Boolean =
        locate(project, run, mutant, applied = true) != null

    fun apply(project: Project, run: TestoMutationRun, mutant: Mutant): Boolean =
        replace(project, run, mutant, applied = false, TestoBundle.message("infection.apply.command", mutant.mutator))

    /** Puts the original back: an edit of its own, so it works after a save or a restart, whatever the undo stack holds. */
    fun revert(project: Project, run: TestoMutationRun, mutant: Mutant): Boolean =
        replace(project, run, mutant, applied = true, TestoBundle.message("infection.revert.command", mutant.mutator))

    private fun replace(project: Project, run: TestoMutationRun, mutant: Mutant, applied: Boolean, command: String): Boolean {
        val (file, range) = locate(project, run, mutant, applied) ?: return false
        val found = (if (applied) mutant.mutated else mutant.original) ?: return false
        val wanted = (if (applied) mutant.original else mutant.mutated) ?: return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val replacement = if (range.length == found.length) wanted else wanted.removeSuffix("\n")
        WriteCommandAction.runWriteCommandAction(project, command, null, {
            document.replaceString(range.startOffset, range.endOffset, replacement)
        })
        val line = mutant.lines?.first ?: mutant.firstLine ?: return true
        OpenFileDescriptor(project, file, line - 1, 0).navigate(true)
        return true
    }

    private fun locate(project: Project, run: TestoMutationRun, mutant: Mutant, applied: Boolean): Pair<VirtualFile, TextRange>? {
        val original = mutant.original ?: return null
        val mutated = mutant.mutated ?: return null
        if (original == mutated) return null
        val firstLine = mutant.firstLine ?: return null
        val file = run.localPath(mutant.file.path)?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return null
        if (!mutationFileBelongsTo(project, file)) return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val range = snippetRange(document.charsSequence, firstLine, if (applied) mutated else original) ?: return null
        return file to range
    }
}

internal fun mutationFileBelongsTo(project: Project, file: VirtualFile): Boolean =
    ProjectFileIndex.getInstance(project).isInContent(file.canonicalFile ?: file)

/** Where [snippet] sits in [text] when it starts at the 1-based [firstLine], or null when the text there differs. */
internal fun snippetRange(text: CharSequence, firstLine: Int, snippet: String): TextRange? {
    if (firstLine < 1 || snippet.isEmpty()) return null
    var start = 0
    repeat(firstLine - 1) {
        val next = text.indexOf('\n', start)
        if (next < 0) return null
        start = next + 1
    }
    // The log closes every snippet line with a newline; the file's last line may have none.
    val candidates = listOf(snippet, snippet.removeSuffix("\n"))
    return candidates.firstNotNullOfOrNull { candidate ->
        val end = start + candidate.length
        TextRange(start, end).takeIf { end <= text.length && text.subSequence(start, end).contentEquals(candidate) }
    }
}
