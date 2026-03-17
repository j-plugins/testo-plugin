package com.github.xepozz.testo

import junit.framework.TestCase

class TestoClassesTest : TestCase() {

    fun testTestAttributes_containsExpectedValues() {
        val attrs = TestoClasses.TEST_ATTRIBUTES
        assertEquals(2, attrs.size)
        assertTrue(attrs.contains("\\Testo\\Attribute\\Test"))
        assertTrue(attrs.contains("\\Testo\\Application\\Attribute\\Test"))
    }

    fun testTestInlineAttributes_containsExpectedValues() {
        val attrs = TestoClasses.TEST_INLINE_ATTRIBUTES
        assertEquals(2, attrs.size)
        assertTrue(attrs.contains("\\Testo\\Sample\\TestInline"))
        assertTrue(attrs.contains("\\Testo\\Inline\\TestInline"))
    }

    fun testDataAttributes_containsAllDataTypes() {
        val attrs = TestoClasses.DATA_ATTRIBUTES
        assertEquals(7, attrs.size)
        assertTrue(attrs.contains("\\Testo\\Sample\\DataProvider"))
        assertTrue(attrs.contains("\\Testo\\Data\\DataProvider"))
        assertTrue(attrs.contains("\\Testo\\Sample\\DataSet"))
        assertTrue(attrs.contains("\\Testo\\Data\\DataSet"))
        assertTrue(attrs.contains("\\Testo\\Data\\DataUnion"))
        assertTrue(attrs.contains("\\Testo\\Data\\DataCross"))
        assertTrue(attrs.contains("\\Testo\\Data\\DataZip"))
    }

    fun testBenchAttributes_containsExpectedValues() {
        val attrs = TestoClasses.BENCH_ATTRIBUTES
        assertEquals(2, attrs.size)
        assertTrue(attrs.contains("\\Testo\\Bench\\Bench"))
        assertTrue(attrs.contains("\\Testo\\Bench\\BenchWith"))
    }

    fun testConstants_bench() {
        assertEquals("\\Testo\\Bench\\Bench", TestoClasses.BENCH)
    }

    fun testConstants_assertionException() {
        assertEquals("\\Testo\\Assert\\State\\Assertion\\AssertionException", TestoClasses.ASSERTION_EXCEPTION)
    }

    fun testConstants_assert() {
        assertEquals("\\Testo\\Assert", TestoClasses.ASSERT)
    }

    fun testConstants_expect() {
        assertEquals("\\Testo\\Expect", TestoClasses.EXPECT)
    }

    fun testDataAttributes_noOverlapWithTestAttributes() {
        val dataSet = TestoClasses.DATA_ATTRIBUTES.toSet()
        val testSet = TestoClasses.TEST_ATTRIBUTES.toSet()
        val inlineSet = TestoClasses.TEST_INLINE_ATTRIBUTES.toSet()
        val benchSet = TestoClasses.BENCH_ATTRIBUTES.toSet()

        assertTrue(dataSet.intersect(testSet).isEmpty())
        assertTrue(dataSet.intersect(inlineSet).isEmpty())
        assertTrue(dataSet.intersect(benchSet).isEmpty())
        assertTrue(testSet.intersect(inlineSet).isEmpty())
        assertTrue(testSet.intersect(benchSet).isEmpty())
        assertTrue(inlineSet.intersect(benchSet).isEmpty())
    }
}
