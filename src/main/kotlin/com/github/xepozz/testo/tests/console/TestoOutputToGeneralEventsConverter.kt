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
            TEST_STARTED -> {
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
        private const val TEST_STARTED = "testStarted"
        private const val TEST_STD_OUT = "testStdOut"
        private const val TEST_STD_ERR = "testStdErr"
        private const val TEST_FAILED = "testFailed"
        private const val BUILD_PROBLEM = "buildProblem"
    }
}
