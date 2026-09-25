package com.github.xepozz.testo.tests.console

import java.util.concurrent.ConcurrentHashMap

/**
 * Names the channel a chunk opens a tab for: the first chunk of a channel outside [tabbed] that [shows] accepts.
 * Each channel is named once. Safe to feed from the test-reader thread.
 */
internal class NewChannelDetector(
    tabbed: Collection<String>,
    private val shows: (ChannelOutputStore.Chunk) -> Boolean,
) {
    private val known = ConcurrentHashMap.newKeySet<String>().apply { addAll(tabbed) }

    fun offer(chunk: ChannelOutputStore.Chunk): String? {
        val channel = chunk.channel ?: return null
        return channel.takeIf { shows(chunk) && known.add(it) }
    }
}
