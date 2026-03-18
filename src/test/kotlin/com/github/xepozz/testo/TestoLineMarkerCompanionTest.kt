package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import junit.framework.TestCase

class TestoLineMarkerCompanionTest : TestCase() {

    fun testRunnableAttributes_containsAllDataAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.DATA_ATTRIBUTES) {
            assertTrue("Missing data attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_containsBenchAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.BENCH_ATTRIBUTES) {
            assertTrue("Missing bench attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_containsBenchAttribute() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        assertTrue("Should contain \\Testo\\Bench", runnable.contains(TestoClasses.BENCH))
    }

    fun testRunnableAttributes_containsTestInline() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        assertTrue("Missing TestInline attribute", runnable.contains(TestoClasses.TEST_INLINE))
    }

    fun testRunnableAttributes_doesNotContainPlainTestAttribute() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        assertFalse("Should not contain plain Test attribute", runnable.contains(TestoClasses.TEST))
    }

    fun testRunnableAttributes_totalCount() {
        val expected = TestoClasses.DATA_ATTRIBUTES.size +
                TestoClasses.BENCH_ATTRIBUTES.size +
                1 // TestInline
        assertEquals(expected, TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.size)
    }
}
