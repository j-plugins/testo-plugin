package com.github.xepozz.testo

import junit.framework.TestCase

class MixinExtensionsTest : TestCase() {

    fun testTakeWhileInclusive_sequence_stopsAfterFalse() {
        val result = sequenceOf(1, 2, 3, 4, 5).takeWhileInclusive { it < 3 }.toList()
        assertEquals(listOf(1, 2, 3), result)
    }

    fun testTakeWhileInclusive_sequence_allTrue() {
        val result = sequenceOf(1, 2, 3).takeWhileInclusive { it < 10 }.toList()
        assertEquals(listOf(1, 2, 3), result)
    }

    fun testTakeWhileInclusive_sequence_firstFalse() {
        val result = sequenceOf(5, 1, 2).takeWhileInclusive { it < 3 }.toList()
        assertEquals(listOf(5), result)
    }

    fun testTakeWhileInclusive_sequence_empty() {
        val result = emptySequence<Int>().takeWhileInclusive { it < 3 }.toList()
        assertEquals(emptyList<Int>(), result)
    }

    fun testTakeWhileInclusive_collection_stopsAfterFalse() {
        val result = listOf(1, 2, 3, 4, 5).takeWhileInclusive { it < 3 }
        assertEquals(listOf(1, 2, 3), result.toList())
    }

    fun testTakeWhileInclusive_collection_allTrue() {
        val result = listOf(1, 2).takeWhileInclusive { it < 10 }
        assertEquals(listOf(1, 2), result.toList())
    }

    fun testTakeWhileInclusive_collection_empty() {
        val result = emptyList<Int>().takeWhileInclusive { it < 3 }
        assertTrue(result.isEmpty())
    }

    fun testTakeWhileInclusive_collection_singleElement_true() {
        val result = listOf(1).takeWhileInclusive { it < 3 }
        assertEquals(listOf(1), result.toList())
    }

    fun testTakeWhileInclusive_collection_singleElement_false() {
        val result = listOf(10).takeWhileInclusive { it < 3 }
        assertEquals(listOf(10), result.toList())
    }
}
