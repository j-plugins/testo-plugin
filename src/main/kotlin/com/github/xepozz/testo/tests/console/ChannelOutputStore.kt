package com.github.xepozz.testo.tests.console

class ChannelOutputStore {
    data class Chunk(val text: String, val level: String?)

    private val lock = Any()
    private val byTest = HashMap<String, LinkedHashMap<String, MutableList<Chunk>>>()
    private val allByTest = HashMap<String, MutableList<Chunk>>()
    private val outputByTest = HashMap<String, MutableList<Chunk>>()
    private val iconByChannel = HashMap<String, String>()
    private val colorByChannel = HashMap<String, String>()

    fun append(testKey: String, channel: String, text: String, level: String?) {
        synchronized(lock) {
            byTest
                .getOrPut(testKey) { LinkedHashMap() }
                .getOrPut(channel) { mutableListOf() }
                .add(Chunk(text, level))
        }
    }

    fun appendAll(testKey: String, text: String, level: String?) {
        synchronized(lock) {
            allByTest.getOrPut(testKey) { mutableListOf() }.add(Chunk(text, level))
        }
    }

    fun appendOutput(testKey: String, text: String, level: String?) {
        synchronized(lock) {
            outputByTest.getOrPut(testKey) { mutableListOf() }.add(Chunk(text, level))
        }
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
            return channels.entries.associateTo(LinkedHashMap()) { (name, chunks) -> name to chunks.toList() }
        }
    }

    fun allFor(testKey: String): List<Chunk> {
        synchronized(lock) { return allByTest[testKey]?.toList() ?: emptyList() }
    }

    fun outputFor(testKey: String): List<Chunk> {
        synchronized(lock) { return outputByTest[testKey]?.toList() ?: emptyList() }
    }

    fun clear() {
        synchronized(lock) {
            byTest.clear()
            allByTest.clear()
            outputByTest.clear()
            iconByChannel.clear()
            colorByChannel.clear()
        }
    }
}
