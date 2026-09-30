package com.github.xepozz.testo.infection

import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.coverage.format.childElements
import com.github.xepozz.testo.coverage.format.descendants
import com.github.xepozz.testo.coverage.format.readXmlRoot
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The tests behind a mutant: the ones that ran over its code, and of those the ones that failed on it. */
internal class TestoMutantTests(val covering: List<TestId>, val killing: List<TestId>) {
    companion object {
        /**
         * The tests whose failure killed the mutant. Testo's Infection adapter prints each run's result as one JSON
         * object, `failures[].test` naming a test as coverage does; anything else in the output is not a failure.
         */
        fun killing(output: String?): List<TestId> {
            val start = output?.indexOf('{')?.takeIf { it >= 0 } ?: return emptyList()
            return runCatching {
                val reader = JsonReader(StringReader(output.substring(start))).apply { isLenient = true }
                JsonParser.parseReader(reader).asJsonObject.getAsJsonArray("failures")
                    ?.mapNotNull { failure -> failure.asJsonObject.get("test")?.asString?.let(TestId::parse) }
                    .orEmpty()
                    .distinct()
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * Which tests ran over which line, off the coverage-xml a mutation run was started from: the same data Infection picked
 * each mutant's tests by. A file's report is read the first time one of its mutants is asked about.
 */
internal class TestoCoveringTestIndex(private val coverageXml: Path) {
    private val sources: List<String> by lazy {
        runCatching { TestoInfectionReports.coveredSourceFiles(coverageXml) }.getOrDefault(emptyList())
    }

    private val byFile = ConcurrentHashMap<String, Map<Int, List<TestId>>>()

    /** The tests covering [lines] of the mutated file at [path], as the interpreter saw it. */
    fun covering(path: String, lines: IntRange): List<TestId> {
        val source = sourceOf(path, sources) ?: return emptyList()
        val byLine = byFile.getOrPut(source) { read(source) }
        return lines.flatMap { byLine[it].orEmpty() }.distinct()
    }

    private fun read(source: String): Map<Int, List<TestId>> = runCatching {
        val report = coverageXml.resolve("$source.xml").takeIf(Files::isRegularFile) ?: return emptyMap()
        readXmlRoot(report).descendants("line").mapNotNull { line ->
            val number = line.getAttribute("nr").toIntOrNull() ?: return@mapNotNull null
            number to line.childElements("covered").mapNotNull { TestId.parse(it.getAttribute("by")) }
        }.toMap()
    }.getOrDefault(emptyMap())
}
