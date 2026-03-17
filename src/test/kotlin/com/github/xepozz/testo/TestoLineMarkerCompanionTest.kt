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

    fun testRunnableAttributes_containsInlineTestAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.TEST_INLINE_ATTRIBUTES) {
            assertTrue("Missing inline test attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_containsBenchAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.BENCH_ATTRIBUTES) {
            assertTrue("Missing bench attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_doesNotContainPlainTestAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.TEST_ATTRIBUTES) {
            assertFalse("Should not contain plain test attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_totalCount() {
        val expected = TestoClasses.DATA_ATTRIBUTES.size +
                TestoClasses.TEST_INLINE_ATTRIBUTES.size +
                TestoClasses.BENCH_ATTRIBUTES.size
        assertEquals(expected, TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.size)
    }
}
