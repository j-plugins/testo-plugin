package com.github.xepozz.testo

import com.github.xepozz.testo.util.PsiUtil
import junit.framework.TestCase

class PsiUtilTest : TestCase() {

    fun testMeaningfulAttributes_containsAllGroups() {
        val meaningful = PsiUtil.MEANINGFUL_ATTRIBUTES.toSet()

        for (attr in TestoClasses.DATA_ATTRIBUTES) {
            assertTrue("Missing data attribute: $attr", meaningful.contains(attr))
        }
        for (attr in TestoClasses.TEST_ATTRIBUTES) {
            assertTrue("Missing test attribute: $attr", meaningful.contains(attr))
        }
        for (attr in TestoClasses.BENCH_ATTRIBUTES) {
            assertTrue("Missing bench attribute: $attr", meaningful.contains(attr))
        }
    }

    fun testMeaningfulAttributes_totalCount() {
        val expected = TestoClasses.DATA_ATTRIBUTES.size +
                TestoClasses.TEST_ATTRIBUTES.size +
                TestoClasses.BENCH_ATTRIBUTES.size
        assertEquals(expected, PsiUtil.MEANINGFUL_ATTRIBUTES.size)
    }

    fun testMeaningfulAttributes_noDuplicates() {
        val attrs = PsiUtil.MEANINGFUL_ATTRIBUTES
        assertEquals(attrs.size, attrs.toSet().size)
    }
}
