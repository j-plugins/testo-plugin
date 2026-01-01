package com.github.xepozz.testo.tests

import com.github.xepozz.testo.takeWhileInclusive
import com.intellij.execution.testframework.sm.runner.ui.TestStackTraceParser
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.DocumentUtil
import com.jetbrains.php.phpunit.PhpUnitQualifiedNameLocationProvider
import com.jetbrains.rd.generator.nova.GenerationSpec.Companion.nullIfEmpty

private const val PREFIX = "[internal function]"

class TestoStackTraceParser(
    failedLine: Int,
    failedMethodName: String?,
    errorMessage: String?,
    topLocationLine: String?
) : TestStackTraceParser(failedLine, failedMethodName, errorMessage, topLocationLine) {
    private constructor(errorMessage: String?) : this(-1, null, errorMessage, null)

    companion object {
        fun parse(
            url: String,
            stacktrace: String?,
            errorMessage: String?,
            locator: PhpUnitQualifiedNameLocationProvider,
            project: Project
        ): TestoStackTraceParser {
            if (stacktrace.isNullOrEmpty()) return TestoStackTraceParser(errorMessage)

            val lines = stacktrace
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .takeWhileInclusive { !it.contains(PREFIX) }
                .filter { !it.isEmpty() }

            if (lines.isEmpty()) return TestoStackTraceParser(errorMessage)

            val errorMessage = if (errorMessage.isNullOrEmpty()) lines.first() else errorMessage.nullIfEmpty()

            val parts = url.substringAfter("${TestoFrameworkType.SCHEMA}://").split("::")
            val path = parts.getOrNull(0) ?: return TestoStackTraceParser(errorMessage)
            val classFqn = parts.getOrNull(1)
            val classMethod = parts.getOrNull(2)

            val lastLine = lines[lines.size - 1].trim { it in listOf(' ', '(', ')') }

            val failedLine = lines[lines.size - 2]
                .substringAfter(path)
                .substringAfter('(')
                .substringBefore(')')
                .toIntOrNull()
                ?: -1
            val failedLineText = getLineText(path, failedLine, project, locator)

            if (lastLine.contains("->")) {
                return TestoStackTraceParser(failedLine, failedLineText, errorMessage, null)
            }

            return TestoStackTraceParser(failedLine, failedLineText, errorMessage, null)
        }

        private fun getLineText(
            path: String,
            line: Int,
            project: Project,
            locator: PhpUnitQualifiedNameLocationProvider
        ): String? {
            val vFile = locator.pathMapper.getLocalFile(path) ?: return null
            val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return null
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

            if (line < 1 || line > document.lineCount) return null

            val range = DocumentUtil.getLineTextRange(document, line - 1)

            return document.getText(range)
        }
    }
}