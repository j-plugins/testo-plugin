package com.github.xepozz.testo.tests

/** The `php_qn://…` addresses Testo reports tests by, and the plugin's run icons point at. */
object TestoLocationHints {
    const val SCHEMA = "php_qn"

    /** An address read back: the file as the interpreter named it, and the class (or function) and method in it. */
    data class Parsed(val filePath: String, val className: String?, val methodName: String?)

    /**
     * Reads the part of an address after `php_qn://`:
     * - path/to/file.php
     * - path/to/file.php::\Full\Qualified\ClassName
     * - path/to/file.php::\Full\Qualified\ClassName::methodName
     * - path/to/file.php::\Full\Qualified\FunctionName
     *
     * The name in either of the last two positions may carry the data pointer Testo appends to reach one data set —
     * see [stripTestoCoordinates], which takes it back off. The file is never stripped: a Windows path holds a colon of
     * its own, and it names no PHP symbol anyway. Null for an address of more than three parts.
     */
    fun parse(link: String): Parsed? {
        val locations = link.split("::").dropLastWhile { it.isEmpty() }

        return when (locations.size) {
            1 -> Parsed(locations[0], null, null)
            2 -> Parsed(locations[0], stripTestoCoordinates(locations[1]), null)
            3 -> Parsed(locations[0], stripTestoCoordinates(locations[1]), stripTestoCoordinates(locations[2]))
            else -> null
        }
    }

    /**
     * The name a hint segment declares, without the coordinates appended to it.
     *
     * `\Ns\Calculator::med:3:0` names data set #0 of attribute #3; PHP declares no such member, so a lookup by name
     * misses and the IDE answers with the enclosing class — or with the file, for a standalone function. An identifier
     * cannot contain a colon, so the first one starts the coordinates.
     *
     * Navigation stops at the method: `:3` numbers the attribute within its own group, and which group that is comes
     * from the run's `--type`, which the hint does not carry.
     *
     * `#<index>` and ` with data set #N` are the plugin's own display suffixes and go too.
     */
    fun stripTestoCoordinates(segment: String): String? = segment
        .substringBefore(" with data set")
        .substringBefore('#')
        .substringBefore(':')
        .trim()
        .ifEmpty { null }
}
