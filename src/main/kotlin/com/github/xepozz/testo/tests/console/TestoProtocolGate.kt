package com.github.xepozz.testo.tests.console

/**
 * The service-message protocol this plugin needs, and how to tell an older Testo apart from it.
 *
 * `TestoConsoleProperties.isIdBasedTestTree()` is true, so the platform builds the tree with
 * `GeneralIdBasedToSMTRunnerEventsConvertor` and every node-bearing message has to carry `nodeId`/`parentNodeId`.
 * Testo only started emitting them in [MINIMUM_VERSION]; an older build sends the same messages name-based, and the
 * platform convertor logs one error per message ("Missing nodeId", "Parent node id should be defined") — hundreds of
 * them, surfacing as an IDE internal error rather than as anything the user can act on.
 *
 * So the gate is the presence of `nodeId` itself, not a version comparison: it holds for forks and nightly builds too,
 * and it needs nothing but the message already in hand. The version is only used to word the notification.
 */
object TestoProtocolGate {
    /** First Testo release whose TeamCity formatter tags messages with `nodeId`/`parentNodeId`. */
    const val MINIMUM_VERSION: String = "0.10.39"

    /**
     * Messages that name a node in the test tree — the ones the id-based convertor rejects without a `nodeId`.
     * `blockOpened`/`blockClosed` and friends describe no node and are none of the gate's business.
     */
    private val NODE_MESSAGES = setOf(
        "testSuiteStarted",
        "testSuiteFinished",
        "testStarted",
        "testFinished",
        "testFailed",
        "testIgnored",
        "testStdOut",
        "testStdErr",
    )

    // Anchored on the escape character, so ordinary bracketed text survives — a suite named "Foo [test]" would
    // otherwise lose characters to the sequence pattern.
    private val ANSI = Regex("""\e\[[0-9;]*[a-zA-Z]""")

    private val BANNER = Regex("""\bTesto\s+v?(\d[\w.+-]*)""")

    /** Whether a message of this name has to carry a `nodeId` for the id-based tree to accept it. */
    fun requiresNodeId(messageName: String): Boolean = messageName in NODE_MESSAGES

    /** Whether this message proves the runner speaks the pre-[MINIMUM_VERSION] protocol. */
    fun isLegacyMessage(messageName: String, attributes: Map<String, String>): Boolean =
        requiresNodeId(messageName) && attributes["nodeId"].isNullOrEmpty()

    /**
     * The version off Testo's banner (`Testo v0.10.38`), or null when the text is not one.
     *
     * The banner carries ANSI attributes around both halves even under `-q`, so colouring is stripped first.
     */
    fun parseVersion(text: String): String? =
        BANNER.find(ANSI.replace(text, ""))?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
}
