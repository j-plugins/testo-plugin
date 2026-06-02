package com.github.xepozz.testo.tests.console

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

class PhpBacktraceFileFilter(private val project: Project) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        if ('/' !in line && '\\' !in line) return null
        val lineStart = entireLength - line.length
        val items = ArrayList<Filter.ResultItem>()
        for (pattern in PATTERNS) {
            var match = pattern.find(line)
            while (match != null) {
                linkItem(match, lineStart)?.let(items::add)
                match = match.next()
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun linkItem(match: MatchResult, lineStart: Int): Filter.ResultItem? {
        val lineNumber = match.groupValues[2].toIntOrNull() ?: return null
        val file = LocalFileSystem.getInstance().findFileByPath(match.groupValues[1]) ?: return null
        val start = lineStart + match.range.first
        val end = lineStart + match.range.last + 1
        return Filter.ResultItem(start, end, OpenFileHyperlinkInfo(project, file, lineNumber - 1))
    }

    companion object {
        private const val PATH = """((?:[A-Za-z]:)?[/\\][^\s:()]+)"""

        private val PATTERNS = listOf(
            Regex("""$PATH\((\d+)\)"""),     // #0 /abs/File.php(48): Class::method()
            Regex("""$PATH:(\d+)"""),         // /abs/File.php:48
            Regex("""$PATH on line (\d+)"""), // PHP Fatal error: ... in /abs/File.php on line 48
        )
    }
}
