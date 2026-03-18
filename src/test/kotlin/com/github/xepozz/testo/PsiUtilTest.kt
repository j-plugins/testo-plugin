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

    // --- Attribute group tests ---

    fun testGetAttributeGroup_dataProviderInDataGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.DATA_PROVIDER)
        assertNotNull("DataProvider should have a group", group)
        assertSame(TestoClasses.DATA_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_dataSetInDataGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.DATA_SET)
        assertNotNull("DataSet should have a group", group)
        assertSame(TestoClasses.DATA_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_dataUnionInDataGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.DATA_UNION)
        assertNotNull("DataUnion should have a group", group)
        assertSame(TestoClasses.DATA_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_dataCrossInDataGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.DATA_CROSS)
        assertNotNull("DataCross should have a group", group)
        assertSame(TestoClasses.DATA_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_dataZipInDataGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.DATA_ZIP)
        assertNotNull("DataZip should have a group", group)
        assertSame(TestoClasses.DATA_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_testInlineInInlineGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.TEST_INLINE)
        assertNotNull("TestInline should have a group", group)
        assertSame(TestoClasses.TEST_INLINE_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_benchInBenchGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.BENCH)
        assertNotNull("Bench should have a group", group)
        assertSame(TestoClasses.BENCH_ATTRIBUTES, group)
    }

    fun testGetAttributeGroup_testHasNoGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.TEST)
        assertNull("Plain Test attribute should not have a numbered group", group)
    }

    fun testGetAttributeGroup_nullReturnsNull() {
        assertNull(PsiUtil.getAttributeGroup(null))
    }

    fun testGetAttributeGroup_unknownReturnsNull() {
        assertNull(PsiUtil.getAttributeGroup("\\Some\\Unknown\\Attribute"))
    }

    fun testAttributeGroups_allGroupedAttributesAreInMeaningful() {
        val meaningful = PsiUtil.MEANINGFUL_ATTRIBUTES.toSet()
        for (group in PsiUtil.ATTRIBUTE_GROUPS) {
            for (attr in group) {
                assertTrue("Grouped attribute $attr should be in MEANINGFUL_ATTRIBUTES", meaningful.contains(attr))
            }
        }
    }

    fun testAttributeGroups_noOverlapBetweenGroups() {
        val groups = PsiUtil.ATTRIBUTE_GROUPS
        for (i in groups.indices) {
            for (j in i + 1 until groups.size) {
                val overlap = groups[i].toSet().intersect(groups[j].toSet())
                assertTrue("Groups $i and $j should not overlap, but share: $overlap", overlap.isEmpty())
            }
        }
    }

    fun testAttributeGroups_totalCount() {
        val totalGrouped = PsiUtil.ATTRIBUTE_GROUPS.sumOf { it.size }
        val expectedGrouped = TestoClasses.DATA_ATTRIBUTES.size +
                TestoClasses.TEST_INLINE_ATTRIBUTES.size +
                TestoClasses.BENCH_ATTRIBUTES.size
        assertEquals(expectedGrouped, totalGrouped)
    }
}
