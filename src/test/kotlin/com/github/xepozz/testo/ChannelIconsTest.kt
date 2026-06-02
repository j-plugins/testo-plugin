package com.github.xepozz.testo

import com.github.xepozz.testo.tests.console.ChannelIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit4 tests for [ChannelIcons.MAP] — structural assertions only (no AllIcons identity).
 */
class ChannelIconsTest {

    @Test
    fun mapIsNonEmpty() {
        assertTrue(ChannelIcons.MAP.isNotEmpty())
    }

    @Test
    fun everyValueIsNonNull() {
        for ((key, icon) in ChannelIcons.MAP) {
            assertNotNull("Icon for key '$key' must not be null", icon)
        }
    }

    @Test
    fun everyKeyIsLowerCase() {
        for (key in ChannelIcons.MAP.keys) {
            assertEquals("Key '$key' must be lower-case so lowercase() lookups work", key.lowercase(), key)
        }
    }

    @Test
    fun expectedKeysExist() {
        val expected = listOf(
            "assert", "bench", "sql", "log", "file", "network",
            "stderr", "dump", "coverage", "debug", "testo", "khinkali",
        )
        for (key in expected) {
            assertTrue("Expected key '$key' missing from MAP", ChannelIcons.MAP.containsKey(key))
        }
    }
}
