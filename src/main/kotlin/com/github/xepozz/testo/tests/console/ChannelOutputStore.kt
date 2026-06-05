package com.github.xepozz.testo.tests.console

/**
 * Per-test output model, split into the "all" stream, the plain "output" stream and named channels. Each stream is a
 * [LiveBuffer]: a view can [attachAll]/[attachChannel] to it and, under the same lock that guards appends, atomically
 * replay what's accumulated and then receive every later chunk — so a console keeps streaming live instead of showing
 * a one-time snapshot. Output arrives on a single reader thread, so holding the lock across append+notify preserves
 * order without extra synchronization.
 */
class ChannelOutputStore {
    // channel is carried on the "all" stream too, so the aggregated All tab can pick a per-message language.
    data class Chunk(val text: String, val level: String?, val channel: String? = null)

    private class LiveBuffer {
        private val chunks = mutableListOf<Chunk>()
        private val sinks = mutableListOf<(Chunk) -> Unit>()

        fun append(chunk: Chunk) {
            chunks.add(chunk)
            sinks.forEach { it(chunk) }
        }

        fun snapshot(): List<Chunk> = chunks.toList()

        fun attach(sink: (Chunk) -> Unit): () -> Unit {
            chunks.forEach(sink)
            sinks.add(sink)
            return { sinks.remove(sink) }
        }
    }

    private val lock = Any()
    private val byTest = HashMap<String, LinkedHashMap<String, LiveBuffer>>()
    private val allByTest = HashMap<String, LiveBuffer>()
    private val outputByTest = HashMap<String, LiveBuffer>()
    private val iconByChannel = HashMap<String, String>()
    private val colorByChannel = HashMap<String, String>()

    private var headerChunks: List<Chunk> = emptyList()
    private val locationByName = HashMap<String, String>()

    fun rememberLocation(name: String, location: String) {
        synchronized(lock) { locationByName[name] = location }
    }

    /**
     * The per-run storage key for a test: its location hint (recorded on `testStarted`, before any output or
     * selection) when known, else the bare name. Both the converter (which stores output) and the channel UI (which
     * looks it up) MUST key through this so they always agree — deriving the key from `SMTestProxy.locationUrl`
     * instead fails, because the platform resolves that lazily, so a parent selected mid-run subscribes to the wrong
     * key and sees nothing.
     */
    fun keyFor(name: String): String {
        synchronized(lock) { return locationByName[name] ?: name }
    }

    fun setHeader(chunks: List<Chunk>) {
        synchronized(lock) { headerChunks = chunks }
    }

    fun header(): List<Chunk> {
        synchronized(lock) { return headerChunks }
    }

    fun append(testKey: String, channel: String, text: String, level: String?) {
        synchronized(lock) {
            byTest.getOrPut(testKey) { LinkedHashMap() }.getOrPut(channel) { LiveBuffer() }.append(Chunk(text, level))
        }
    }

    fun appendAll(testKey: String, text: String, level: String?, channel: String? = null) {
        synchronized(lock) { allByTest.getOrPut(testKey) { LiveBuffer() }.append(Chunk(text, level, channel)) }
    }

    fun appendOutput(testKey: String, text: String, level: String?) {
        synchronized(lock) { outputByTest.getOrPut(testKey) { LiveBuffer() }.append(Chunk(text, level)) }
    }

    /** Replays the test's "all" stream into [sink] and keeps feeding it live; returns a detach handle. */
    fun attachAll(testKey: String, sink: (Chunk) -> Unit): () -> Unit = synchronized(lock) {
        val detach = allByTest.getOrPut(testKey) { LiveBuffer() }.attach(sink)
        return@synchronized { synchronized(lock) { detach() } }
    }

    /** Replays the test's plain "output" stream into [sink] and keeps feeding it live; returns a detach handle. */
    fun attachOutput(testKey: String, sink: (Chunk) -> Unit): () -> Unit = synchronized(lock) {
        val detach = outputByTest.getOrPut(testKey) { LiveBuffer() }.attach(sink)
        return@synchronized { synchronized(lock) { detach() } }
    }

    /** Replays the test's [channel] stream into [sink] and keeps feeding it live; returns a detach handle. */
    fun attachChannel(testKey: String, channel: String, sink: (Chunk) -> Unit): () -> Unit = synchronized(lock) {
        val detach = byTest.getOrPut(testKey) { LinkedHashMap() }.getOrPut(channel) { LiveBuffer() }.attach(sink)
        return@synchronized { synchronized(lock) { detach() } }
    }

    fun setChannelIcon(channel: String, icon: String) {
        synchronized(lock) { iconByChannel.putIfAbsent(channel, icon) }
    }

    fun channelIcon(channel: String): String? {
        synchronized(lock) { return iconByChannel[channel] }
    }

    fun setChannelColor(channel: String, color: String) {
        synchronized(lock) { colorByChannel.putIfAbsent(channel, color) }
    }

    fun channelColor(channel: String): String? {
        synchronized(lock) { return colorByChannel[channel] }
    }

    fun channelsFor(testKey: String): Map<String, List<Chunk>> {
        synchronized(lock) {
            val channels = byTest[testKey] ?: return emptyMap()
            return channels.entries.associateTo(LinkedHashMap()) { (name, buffer) -> name to buffer.snapshot() }
        }
    }

    fun allFor(testKey: String): List<Chunk> {
        synchronized(lock) { return allByTest[testKey]?.snapshot() ?: emptyList() }
    }

    fun outputFor(testKey: String): List<Chunk> {
        synchronized(lock) { return outputByTest[testKey]?.snapshot() ?: emptyList() }
    }

    // headerChunks deliberately survives clear(): the header is set once per run, before per-test clears fire.
    fun clear() {
        synchronized(lock) {
            byTest.clear()
            allByTest.clear()
            outputByTest.clear()
            iconByChannel.clear()
            colorByChannel.clear()
            locationByName.clear()
        }
    }
}
