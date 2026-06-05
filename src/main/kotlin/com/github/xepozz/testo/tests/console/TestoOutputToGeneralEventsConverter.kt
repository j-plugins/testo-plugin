package com.github.xepozz.testo.tests.console

import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor

class TestoOutputToGeneralEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    private val store: ChannelOutputStore,
    private val levelFilter: LogLevelFilter,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

    override fun processServiceMessage(message: ServiceMessage, visitor: ServiceMessageVisitor) {
        val attrs = message.attributes

        when (message.messageName) {
            TEST_STARTED -> {
                val name = attrs["name"]
                val location = attrs["locationHint"]
                if (name != null && location != null) store.rememberLocation(name, location)
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
        }

        super.processServiceMessage(message, visitor)
    }

    private fun keyFor(name: String?): String? = name?.let { store.keyFor(it) }

    companion object {
        private const val TEST_STARTED = "testStarted"
        private const val TEST_STD_OUT = "testStdOut"
        private const val TEST_STD_ERR = "testStdErr"
        private const val TEST_FAILED = "testFailed"
    }
}
