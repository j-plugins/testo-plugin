package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.Key
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

class TestoOutputToGeneralEventsConverter(
    testFrameworkName: String,
    private val consoleProperties: TestConsoleProperties,
    private val store: ChannelOutputStore,
    private val levelFilter: LogLevelFilter,
    private val statusStore: TestoStatusStore,
    private val timings: TestoRunTimings,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    /** Version off the banner, kept only to name it in the too-old notification. */
    private var runnerVersion: String? = null

    /** Set once a message arrives without a `nodeId`: from then on nothing is handed to the platform convertor. */
    private var legacyRunner = false

    /** Build problems already surfaced this run, keyed the way TeamCity means them to be deduplicated. */
    private val reportedProblems = HashSet<String>()

    override fun process(text: String, outputType: Key<*>) {
        if (runnerVersion == null) runnerVersion = TestoProtocolGate.parseVersion(text)
        super.process(text, outputType)
    }

    override fun processServiceMessage(message: ServiceMessage, visitor: ServiceMessageVisitor) {
        val attrs = message.attributes

        // A Testo older than the id-based protocol sends every node message without a nodeId, and the platform
        // convertor answers each one with a logged error — a run turns into hundreds of IDE internal errors. Say once
        // what is wrong and stop feeding it: an empty tree beside a clear notification beats that.
        if (legacyRunner) return
        if (TestoProtocolGate.isLegacyMessage(message.messageName, attrs)) {
            legacyRunner = true
            notifyRunnerTooOld()
            return
        }

        when (message.messageName) {
            TEST_COUNT -> attrs["count"]?.toIntOrNull()?.let { statusStore.noteDeclaredTotal(it) }

            TEST_STARTED -> {
                statusStore.noteStarted()
                timings.noteTestStarted()
                val name = attrs["name"]
                val location = attrs["locationHint"]
                if (name != null && location != null) store.rememberLocation(name, location)
                val metainfo = attrs["metainfo"]
                if (name != null && !metainfo.isNullOrBlank()) {
                    store.setDescription(store.keyFor(name), metainfo)
                }
            }

            TEST_STD_OUT, TEST_STD_ERR -> {
                val key = keyFor(attrs["name"])
                val out = attrs["out"] ?: ""
                val level = attrs["level"]
                val channel = attrs["channel"]?.takeIf { it.isNotEmpty() }
                // Record the level so the filter menu can list it; storage keeps every chunk regardless.
                levelFilter.noteSeen(level)
                // Tag the all-stream chunk with its channel so the aggregated All tab can highlight per message.
                if (key != null) store.appendAll(key, out, level, channel)

                if (channel != null && key != null) {
                    attrs["icon"]?.takeIf { it.isNotBlank() }?.let { store.setChannelIcon(channel, it) }
                    attrs["color"]?.takeIf { it.isNotBlank() }?.let { store.setChannelColor(channel, it) }
                    store.append(key, channel, out, level)
                    return
                }
                if (key != null) store.appendOutput(key, out, level)
            }

            TEST_FAILED -> {
                val key = keyFor(attrs["name"])
                if (key != null) {
                    val failMessage = attrs["message"].orEmpty()
                    val details = attrs["details"].orEmpty()
                    val text = if (failMessage.isBlank()) details else "$failMessage\n$details"
                    if (text.isNotBlank()) {
                        store.appendAll(key, "\n$text\n", "stderr")
                        store.appendOutput(key, "\n$text\n", "stderr")
                    }
                }
            }

            BUILD_PROBLEM -> {
                reportBuildProblem(attrs["description"].orEmpty(), attrs["identity"].orEmpty())
                // Not forwarded. The SM runner's visitor knows a fixed list of message names and sends everything
                // else to handleUnexpectedServiceMessage, which logs a problem and echoes the raw
                // `##teamcity[buildProblem …]` line into the console — right under the readable one we just wrote.
                return
            }
        }

        // Testo's own verdict (`status`, lower-case) is finer than the passed / failed / ignored the platform can
        // express, and `assertions` on testFinished is a number it has nowhere to put at all. Only the messages that
        // close a *test* feed the tally: `testSuiteFinished` carries the same attribute with a suite's aggregated
        // outcome, and reading it would count every suite, case and DataProvider batch as one more test.
        if (message.messageName == TEST_FINISHED || message.messageName == TEST_FAILED || message.messageName == TEST_IGNORED) {
            attrs["name"]?.let { name ->
                TestoTestStatus.fromWire(attrs["status"])?.let { statusStore.note(name, it) }
                attrs["assertions"]?.toIntOrNull()?.let { statusStore.noteAssertions(name, it) }
                noteStatusFromMessageKind(message.messageName, name)
            }
        }

        // Where the testing phase ends. A test closes with testFinished whatever its status, so this mark is what
        // separates the run's post-processing from the tests, and `duration` is the test's own share of the clock.
        if (message.messageName == TEST_FINISHED) {
            attrs["name"]?.let { timings.noteTestFinished(store.keyFor(it), attrs["duration"]?.toLongOrNull()) }
        }

        super.processServiceMessage(message, visitor)
    }

    /**
     * The coarse verdict a Testo too old to send `status` still conveys: TeamCity has always had exactly three
     * outcomes, and which message closes the test is what picks between them.
     *
     * `testFinished` follows `testFailed` and `testIgnored` rather than replacing them, so the pass it implies only
     * counts while the test has said nothing else. Anything reported through `status` outranks all of this — the
     * store keeps the two apart, so a newer Testo is never coarsened by the guess.
     */
    private fun noteStatusFromMessageKind(messageName: String, name: String) = when (messageName) {
        TEST_FAILED -> statusStore.noteInferred(name, TestoTestStatus.FAILED, onlyIfAbsent = false)
        TEST_IGNORED -> statusStore.noteInferred(name, TestoTestStatus.SKIPPED, onlyIfAbsent = false)
        TEST_FINISHED -> statusStore.noteInferred(name, TestoTestStatus.PASSED, onlyIfAbsent = true)
        else -> Unit
    }

    private fun keyFor(name: String?): String? = name?.let { store.keyFor(it) }

    /**
     * A problem Testo raises about the run as a whole — an empty run, a bootstrap that failed — rather than about any
     * one test.
     *
     * The message is consumed here rather than forwarded, so the raw line stays out of the console (see the branch
     * that calls this). What the user sees instead is written to all three surfaces below, because which of them
     * they are looking at depends on the run:
     *
     *  - **Output** gets it as uncaptured stderr, which is the only way in when the tree is empty. That is exactly
     *    the `testo.noTests` case: nothing to select, so the channel tabs never build and Output is the platform's
     *    own console. Going out as stderr also colours it red for free.
     *  - **All** gets it as a run-level notice. It belongs to no test, and the key it used to be filed under ("") is
     *    one no view ever looks up — it was written and never shown.
     *  - **A notification**, because a run that executed nothing otherwise looks like a run that simply finished.
     *
     * Reported once per problem. TeamCity's `identity` exists precisely to deduplicate a problem raised more than
     * once; a Testo that sends none falls back to the text.
     */
    private fun reportBuildProblem(description: String, identity: String) {
        val text = description.ifBlank { identity }
        if (text.isBlank()) return
        if (!reportedProblems.add(identity.ifBlank { description })) return

        val line = buildString {
            append("\n⚠ Build problem")
            if (identity.isNotBlank()) append(" [").append(identity).append("]")
            append(": ").append(description).append("\n")
        }
        fireOnUncapturedOutput(line, ProcessOutputTypes.STDERR)
        store.appendNotice(ChannelOutputStore.Chunk(line, "stderr"))

        // No title: the group pops over the Run tool window, so the run it belongs to is already obvious, and
        // Testo's own wording ("No tests were executed") says everything a heading would have to repeat.
        NotificationGroupManager.getInstance().getNotificationGroup("Testo")
            ?.createNotification(text, NotificationType.ERROR)
            ?.notify(consoleProperties.project)
    }

    private fun notifyRunnerTooOld() {
        val version = runnerVersion
        val content = when (version) {
            null -> TestoBundle.message("notification.runner.too.old.unknown", TestoProtocolGate.MINIMUM_VERSION)
            else -> TestoBundle.message("notification.runner.too.old", version, TestoProtocolGate.MINIMUM_VERSION)
        }

        NotificationGroupManager.getInstance().getNotificationGroup("Testo")
            ?.createNotification(TestoBundle.message("notification.runner.too.old.title"), content, NotificationType.WARNING)
            ?.notify(consoleProperties.project)
    }

    companion object {
        private const val TEST_COUNT = "testCount"
        private const val TEST_STARTED = "testStarted"
        private const val TEST_FINISHED = "testFinished"
        private const val TEST_STD_OUT = "testStdOut"
        private const val TEST_STD_ERR = "testStdErr"
        private const val TEST_FAILED = "testFailed"
        private const val TEST_IGNORED = "testIgnored"
        private const val BUILD_PROBLEM = "buildProblem"
    }
}
