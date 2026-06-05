package com.github.xepozz.testo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic verification of [TestoClasses] against the current source: constant FQNs and the membership / size /
 * disjointness of the `*_ATTRIBUTES` arrays the rest of the plugin spreads into RUNNABLE/MEANINGFUL sets.
 */
class TestoClassesTest {

    @Test
    fun constantValues() {
        assertEquals("\\Testo\\Test", TestoClasses.TEST)
        assertEquals("\\Testo\\Inline\\TestInline", TestoClasses.TEST_INLINE)
        assertEquals("\\Testo\\Data\\DataProvider", TestoClasses.DATA_PROVIDER)
        assertEquals("\\Testo\\Data\\DataSet", TestoClasses.DATA_SET)
        assertEquals("\\Testo\\Data\\DataUnion", TestoClasses.DATA_UNION)
        assertEquals("\\Testo\\Data\\DataCross", TestoClasses.DATA_CROSS)
        assertEquals("\\Testo\\Data\\DataZip", TestoClasses.DATA_ZIP)
        assertEquals("\\Testo\\Bench", TestoClasses.BENCH)
        assertEquals("\\Testo\\Application\\Config\\ApplicationConfig", TestoClasses.APPLICATION_CONFIG)
        assertEquals("\\Testo\\Application\\Config\\SuiteConfig", TestoClasses.SUITE_CONFIG)
        assertEquals("\\Testo\\Assert", TestoClasses.ASSERT)
        assertEquals("\\Testo\\Assert\\State\\Assertion\\AssertionException", TestoClasses.ASSERTION_EXCEPTION)
        assertEquals("\\Testo\\Expect", TestoClasses.EXPECT)
    }

    @Test
    fun testAttributes() {
        val attrs = TestoClasses.TEST_ATTRIBUTES
        assertEquals(2, attrs.size)
        assertTrue(attrs.contains(TestoClasses.TEST))
        assertTrue(attrs.contains(TestoClasses.TEST_INLINE))
    }

    @Test
    fun testInlineAttributes() {
        val attrs = TestoClasses.TEST_INLINE_ATTRIBUTES
        assertEquals(1, attrs.size)
        assertTrue(attrs.contains(TestoClasses.TEST_INLINE))
        assertFalse(attrs.contains(TestoClasses.TEST))
    }

    @Test
    fun dataAttributes() {
        val attrs = TestoClasses.DATA_ATTRIBUTES
        assertEquals(5, attrs.size)
        assertTrue(attrs.contains(TestoClasses.DATA_PROVIDER))
        assertTrue(attrs.contains(TestoClasses.DATA_SET))
        assertTrue(attrs.contains(TestoClasses.DATA_UNION))
        assertTrue(attrs.contains(TestoClasses.DATA_CROSS))
        assertTrue(attrs.contains(TestoClasses.DATA_ZIP))
    }

    @Test
    fun benchAttributes() {
        val attrs = TestoClasses.BENCH_ATTRIBUTES
        assertEquals(1, attrs.size)
        assertTrue(attrs.contains(TestoClasses.BENCH))
    }

    @Test
    fun groupsAreDisjoint() {
        val data = TestoClasses.DATA_ATTRIBUTES.toSet()
        val test = TestoClasses.TEST_ATTRIBUTES.toSet()
        val bench = TestoClasses.BENCH_ATTRIBUTES.toSet()
        assertTrue(data.intersect(test).isEmpty())
        assertTrue(data.intersect(bench).isEmpty())
        assertTrue(test.intersect(bench).isEmpty())
    }

    @Test
    fun noArrayHasDuplicates() {
        for (arr in listOf(
            TestoClasses.TEST_ATTRIBUTES,
            TestoClasses.TEST_INLINE_ATTRIBUTES,
            TestoClasses.DATA_ATTRIBUTES,
            TestoClasses.BENCH_ATTRIBUTES,
        )) {
            assertEquals(arr.size, arr.toSet().size)
        }
    }
}
