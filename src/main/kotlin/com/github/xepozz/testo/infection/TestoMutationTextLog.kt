package com.github.xepozz.testo.infection

/**
 * Reads Infection's `--logger-text` report written with `--log-verbosity=all`: the only output that carries the code of
 * every mutant — the TeamCity stream drops it for all but the escaped ones — and the test output each one caused.
 *
 * An entry is a `N) file:line [M] Mutator [ID] hash` header, a unified diff and the process output indented by two
 * spaces. Nothing marks where the diff ends, so the hunk is closed after its [CONTEXT] trailing lines, unless another
 * change follows within the gap sebastian/diff would still have merged into the same hunk.
 */
internal object TestoMutationTextLog {
    class Entry(val original: String, val mutated: String, val output: String)

    private const val CONTEXT = 3
    private val HEADER = Regex("""^\d+\) .+:\d+\s+\[M] \S+ \[ID] (\S+)\s*$""")
    private val UNDERLINE = Regex("^=+$")

    fun parse(text: String): Map<String, Entry> {
        val lines = text.lines()
        val entries = LinkedHashMap<String, Entry>()
        var i = 0
        while (i < lines.size) {
            val hash = HEADER.find(lines[i])?.groupValues?.get(1)
            if (hash == null) {
                i++
                continue
            }
            val end = (i + 1 until lines.size).firstOrNull { isBoundary(lines, it) } ?: lines.size
            parseEntry(lines.subList(i + 1, end))?.let { entries[hash] = it }
            i = end
        }
        return entries
    }

    private fun isBoundary(lines: List<String>, index: Int): Boolean =
        HEADER.matches(lines[index]) || (UNDERLINE.matches(lines.getOrElse(index + 1) { "" }) && lines[index].endsWith(":"))

    private fun parseEntry(body: List<String>): Entry? {
        val start = body.indexOfFirst { it.startsWith("@@") }
        if (start < 0) return null
        val diff = body.drop(start)
        val diffEnd = diffEnd(diff)

        val original = StringBuilder()
        val mutated = StringBuilder()
        for (line in diff.subList(0, diffEnd)) {
            when {
                line.startsWith("@@") -> Unit
                line.startsWith("-") -> original.appendLine(line.substring(1))
                line.startsWith("+") -> mutated.appendLine(line.substring(1))
                else -> {
                    val context = line.removePrefix(" ")
                    original.appendLine(context)
                    mutated.appendLine(context)
                }
            }
        }
        val output = diff.drop(diffEnd)
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n") { it.removePrefix("  ") }
        return Entry(original.toString(), mutated.toString(), output)
    }

    private fun diffEnd(diff: List<String>): Int {
        var lastChange = -1
        var i = 1
        while (i < diff.size) {
            if (isChange(diff[i])) lastChange = i
            else if (lastChange >= 0 && i - lastChange > CONTEXT) {
                val merged = (i until minOf(diff.size, lastChange + 2 * CONTEXT + 2)).any { isChange(diff[it]) }
                if (!merged) return i
            }
            i++
        }
        return diff.size
    }

    private fun isChange(line: String) = line.startsWith("-") || line.startsWith("+") || line.startsWith("@@")
}
