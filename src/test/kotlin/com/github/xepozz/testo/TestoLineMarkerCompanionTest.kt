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

    fun testRunnableAttributes_containsTestAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.TEST_ATTRIBUTES) {
            assertTrue("Missing test attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_containsTestCaseAttributes() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        for (attr in TestoClasses.TEST_CASE_ATTRIBUTES) {
            assertTrue("Missing test case attribute: $attr", runnable.contains(attr))
        }
    }

    fun testRunnableAttributes_doesNotContainFilterGroup() {
        val runnable = TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.toSet()
        assertFalse(
            "#[Group] gets its own marker branch and must not be treated as a test attribute",
            runnable.contains(TestoClasses.FILTER_GROUP)
        )
    }

    fun testRunnableAttributes_totalCount() {
        val expected = TestoClasses.TEST_ATTRIBUTES.size +
                TestoClasses.BENCH_ATTRIBUTES.size +
                TestoClasses.DATA_ATTRIBUTES.size +
                TestoClasses.TEST_CASE_ATTRIBUTES.size
        assertEquals(expected, TestoTestRunLineMarkerProvider.RUNNABLE_ATTRIBUTES.size)
    }
}
