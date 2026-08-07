package com.github.xepozz.testo.tests.console

import com.github.xepozz.testo.TestoBundle
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
                val description = attrs["description"].orEmpty()
                val identity = attrs["identity"].orEmpty()
                val text = buildString {
                    append("\n⚠ Build problem")
                    if (identity.isNotBlank()) append(" [").append(identity).append("]")
                    append(": ").append(description).append("\n")
                }
                store.appendAll("", text, "stderr")
                store.appendOutput("", text, "stderr")
            }
        }

        // Testo's own verdict (`status`, lower-case, on testFinished/testFailed) is finer than the passed / failed /
        // ignored the platform can express, and `assertions` on testFinished is a number it has nowhere to put at all.
        // Both are read wherever they turn up rather than pinned to one message name — and after the branch above, so
        // `testStarted` has already registered the location the key comes from.
        attrs["name"]?.let { name ->
            TestoTestStatus.fromWire(attrs["status"])?.let { statusStore.note(name, it) }
            attrs["assertions"]?.toIntOrNull()?.let { statusStore.noteAssertions(name, it) }
        }

        // Where the testing phase ends. A test closes with testFinished whatever its status, so this mark is what
        // separates the run's post-processing from the tests, and `duration` is the test's own share of the clock.
        if (message.messageName == TEST_FINISHED) {
            attrs["name"]?.let { timings.noteTestFinished(store.keyFor(it), attrs["duration"]?.toLongOrNull()) }
        }

        super.processServiceMessage(message, visitor)
    }

    private fun keyFor(name: String?): String? = name?.let { store.keyFor(it) }

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
        private const val BUILD_PROBLEM = "buildProblem"
    }
}
