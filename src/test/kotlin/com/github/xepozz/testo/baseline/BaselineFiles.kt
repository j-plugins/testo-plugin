package com.github.xepozz.testo.baseline

import org.junit.Assert.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/**
 * Text baselines of behaviour that must not change while the code underneath is reorganised: which run a context
 * produces, the command a run gets, how a configuration is named and saved, where a gutter icon points.
 *
 * A baseline is only ever written on request (`TESTO_BASELINE_UPDATE=true` in the environment — the test JVM inherits
 * Gradle's environment, not its `-D` properties), and a missing one fails the test, so a baseline cannot quietly come
 * into being from whatever the code happens to do.
 */
object BaselineFiles {
    private val DIR: Path = Path.of("src/test/testData/baseline")

    fun assertMatches(name: String, actual: String) {
        val file = DIR.resolve("$name.txt")
        val text = actual.trimEnd() + "\n"
        if (System.getenv("TESTO_BASELINE_UPDATE") == "true") {
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            return
        }
        check(Files.exists(file)) { "No baseline $file; run with TESTO_BASELINE_UPDATE=true and review it" }
        assertEquals("Baseline $name differs", Files.readString(file), text)
    }
}
