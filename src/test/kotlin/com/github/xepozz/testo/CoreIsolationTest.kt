package com.github.xepozz.testo

import junit.framework.TestCase
import java.io.File

/**
 * The core — `src/main/kotlin` and `src/test/kotlin` — reaches PHP only through [com.github.xepozz.testo.php.TestoPhp],
 * so it builds against any PHP plugin that implements it. Any mention of a PHP plugin or of an implementation, in code
 * or in a comment, fails here with its file and line. The implementations — PhpStorm's in `src/phpstorm*`, OpenIDE's in
 * `src/openide*` — never mention each other either. The check reads the sources, so every build variant makes it for
 * all of them.
 */
class CoreIsolationTest : TestCase() {

    fun testCoreDoesNotReferenceThePhpPlugin() {
        assertIsolated(CORE_ROOTS, PHPSTORM + OPENIDE, "The core must not reference a PHP plugin or an implementation")
    }

    fun testTheOpenIdeImplementationDoesNotReferencePhpStorm() {
        assertIsolated(OPENIDE_ROOTS, PHPSTORM, "The OpenIDE implementation must not reference PhpStorm's")
    }

    fun testThePhpStormImplementationDoesNotReferenceOpenIde() {
        assertIsolated(PHPSTORM_ROOTS, OPENIDE, "The PhpStorm implementation must not reference OpenIDE's")
    }

    private fun assertIsolated(roots: List<String>, forbidden: List<Regex>, message: String) {
        val files = roots.flatMap { root ->
            val dir = File(root)
            assertTrue("$root is not a directory — the test must run from the project root", dir.isDirectory)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertFalse("No sources found in $roots", files.isEmpty())

        val violations = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.any { it.containsMatchIn(line) }) "${file.path}:${index + 1}: ${line.trim()}" else null
            }
        }

        assertTrue("$message:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    private companion object {
        val CORE_ROOTS = listOf("src/main/kotlin", "src/test/kotlin")
        val PHPSTORM_ROOTS = listOf("src/phpstorm/kotlin", "src/phpstormTest/kotlin")
        val OPENIDE_ROOTS = listOf("src/openide/kotlin", "src/openideTest/kotlin")

        /** PhpStorm's PHP plugin and the implementation on it. */
        val PHPSTORM = listOf(
            Regex("""\bcom\.jetbrains\.php\b"""),
            Regex("""\bcom\.github\.xepozz\.testo\.phpstorm\b"""),
        )

        /** The PHP for OpenIDE plugin and the implementation on it. */
        val OPENIDE = listOf(
            Regex("""\bru\.openide\.openphp\b"""),
            Regex("""\bcom\.github\.xepozz\.testo\.openide\b"""),
        )
    }
}
