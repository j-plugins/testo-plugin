package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.runs.TestoRunRecording
import com.github.xepozz.testo.runs.TestoRunStore
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.GeneralTestEventsProcessor
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
    private val targetStore: TestoTargetStore,
    private val nodes: TestoNodeIndex,
    private val reportStore: TestoReportStore,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    /** Hooked the moment the platform hands the processor over, which is before any output is read. */
    override fun setProcessor(processor: GeneralTestEventsProcessor?) {
        super.setProcessor(processor)
        processor?.let { nodes.attachTo(it) }
    }

    /** Version off the banner, kept only to name it in the too-old notification. */
    private var runnerVersion: String? = null

    /** Set once a message arrives without a `nodeId`: from then on nothing is handed to the platform convertor. */
    private var legacyRunner = false

    /** Build problems already surfaced this run, keyed the way TeamCity means them to be deduplicated. */
    private val reportedProblems = HashSet<String>()

    /** Hint by node id, for the channel store alone: it keys a description like the output it belongs to. */
    private val hintByNodeId = HashMap<String, String>()

    private val testoProperties: com.github.xepozz.testo.tests.TestoConsoleProperties?
        get() = consoleProperties as? com.github.xepozz.testo.tests.TestoConsoleProperties

    override fun process(text: String, outputType: Key<*>) {
        if (runnerVersion == null) runnerVersion = TestoProtocolGate.parseVersion(text)
        // Second route: a message behind a colour escape never reaches parseServiceMessage. The store dedups by path.
        TestoReportRef.fromServiceMessageLine(text)?.let { reportStore.note(it) }
        recordChunk(text, outputType)
        super.process(text, outputType)
    }

    // The converter is the one place every output chunk flows through, from the very first byte (the console attaches
    // before startNotify) — so the run archive records here rather than off a ProcessListener added later.
    private fun recordChunk(text: String, outputType: Key<*>) {
        val props = testoProperties ?: return
        if (props.replayMode) return
        val recording = props.recording ?: synchronized(props) {
            props.recording ?: runCatching {
                TestoRunStore.getInstance(props.project).beginRun(props.configuration.name, props.executor.id)
            }.getOrNull()?.also { props.recording = it }
        } ?: return
        val stream = when {
            ProcessOutputType.isStderr(outputType) -> TestoRunRecording.STDERR
            ProcessOutputType.isStdout(outputType) -> TestoRunRecording.STDOUT
            else -> TestoRunRecording.SYSTEM
        }
        runCatching { recording.appendChunk(stream, text) }
    }

    override fun processServiceMessage(message: ServiceMessage, visitor: ServiceMessageVisitor) {
        val attrs = message.attributes

        // A pre-id-based Testo sends no nodeId, and the platform convertor logs an error per message — hundreds of
        // IDE internal errors per run. Say once what is wrong and stop feeding it.
        if (legacyRunner) return
        if (TestoProtocolGate.isLegacyMessage(message.messageName, attrs)) {
            legacyRunner = true
            notifyRunnerTooOld()
            return
        }

        when (message.messageName) {
            TEST_COUNT -> attrs["count"]?.toIntOrNull()?.let { statusStore.noteDeclaredTotal(it) }

            // A suite message opens a node the same way a test message does, carrying the same optional attributes.
            TEST_STARTED, TEST_SUITE_STARTED -> {
                if (message.messageName == TEST_STARTED) {
                    statusStore.noteStarted()
                    timings.noteTestStarted()
                }
                val name = attrs["name"]
                val location = attrs["locationHint"]
                val nodeId = attrs["nodeId"]
                if (name != null) {
                    if (location != null) {
                        store.rememberLocation(name, location)
                        if (nodeId != null) hintByNodeId[nodeId] = location
                        // What the archive is looked up by: which tests this run holds ("Show history" asks that).
                        testoProperties?.recording?.noteLocation(location)
                    }
                    val metainfo = attrs["metainfo"]
                    if (!metainfo.isNullOrBlank()) store.setDescription(descriptionKey(attrs), metainfo)
                    targetStore.note(nodeId, TestoRunTarget(location, attrs["testSuite"], attrs["testType"]))
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

            // Testo's own message, naming a report of this run. Not forwarded, for the same reason as buildProblem.
            TESTO_REPORT -> {
                TestoReportRef.fromAttributes(attrs)?.let { reportStore.note(it) }
                return
            }

            BUILD_PROBLEM -> {
                reportBuildProblem(attrs["description"].orEmpty(), attrs["identity"].orEmpty())
                // Not forwarded: the visitor sends unknown names to handleUnexpectedServiceMessage, which echoes the
                // raw `##teamcity[buildProblem …]` line right under the readable one we just wrote.
                return
            }
        }

        // A group node closes with its children's outcome rolled up: worth drawing, but filed apart from the tally —
        // one arrives per case, per batch and per suite, and counting them as tests would inflate every number.
        if (message.messageName == TEST_SUITE_FINISHED) {
            attrs["nodeId"]?.let { nodeId ->
                TestoTestStatus.fromWire(attrs["status"])?.let { statusStore.noteSuite(nodeId, it) }
            }
        }

        // `status` is finer than the platform's passed / failed / ignored, and `assertions` has nowhere to go at all.
        // Only test-closing messages feed the tally — see the suite branch above.
        if (message.messageName == TEST_FINISHED || message.messageName == TEST_FAILED || message.messageName == TEST_IGNORED) {
            attrs["nodeId"]?.let { nodeId ->
                TestoTestStatus.fromWire(attrs["status"])?.let { statusStore.note(nodeId, it) }
                attrs["assertions"]?.toIntOrNull()?.let { statusStore.noteAssertions(nodeId, it) }
                noteStatusFromMessageKind(message.messageName, nodeId)
            }
        }

        // Every test closes with testFinished, so this mark separates the run's post-processing from the tests.
        if (message.messageName == TEST_FINISHED) {
            attrs["nodeId"]?.let { timings.noteTestFinished(it, attrs["duration"]?.toLongOrNull()) }
        }

        super.processServiceMessage(message, visitor)
    }

    /** The channel store's key: the hint, falling back to the name. Not the node id — the channel tabs look it up. */
    private fun descriptionKey(attrs: Map<String, String>): String =
        attrs["nodeId"]?.let { hintByNodeId[it] } ?: store.keyFor(attrs["name"].orEmpty())

    /**
     * The verdict of a Testo too old to send `status`, off which message closed the test — TeamCity's three
     * outcomes. `testFinished` follows the other two rather than replacing them, hence `onlyIfAbsent`.
     */
    private fun noteStatusFromMessageKind(messageName: String, key: String) = when (messageName) {
        TEST_FAILED -> statusStore.noteInferred(key, TestoTestStatus.FAILED, onlyIfAbsent = false)
        TEST_IGNORED -> statusStore.noteInferred(key, TestoTestStatus.SKIPPED, onlyIfAbsent = false)
        TEST_FINISHED -> statusStore.noteInferred(key, TestoTestStatus.PASSED, onlyIfAbsent = true)
        else -> Unit
    }

    private fun keyFor(name: String?): String? = name?.let { store.keyFor(it) }

    /**
     * A problem about the run as a whole — an empty run, a failed bootstrap — rather than about one test.
     *
     * Written to three surfaces, since which one the user is looking at depends on the run: **Output** as uncaptured
     * stderr (the only way in when the tree is empty, and red for free), **All** as a run-level notice, and a
     * **notification**, because a run that executed nothing otherwise looks like one that simply finished.
     *
     * Deduplicated by TeamCity's `identity`, falling back to the text.
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
        private const val TEST_SUITE_STARTED = "testSuiteStarted"
        private const val TEST_SUITE_FINISHED = "testSuiteFinished"
        private const val TEST_FINISHED = "testFinished"
        private const val TEST_STD_OUT = "testStdOut"
        private const val TEST_STD_ERR = "testStdErr"
        private const val TEST_FAILED = "testFailed"
        private const val TEST_IGNORED = "testIgnored"
        private const val BUILD_PROBLEM = "buildProblem"
        private const val TESTO_REPORT = "testoReport"
    }
}
