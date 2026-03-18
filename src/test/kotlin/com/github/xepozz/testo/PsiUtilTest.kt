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

    fun testGetAttributeGroup_testInlineHasGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.TEST_INLINE)
        assertNotNull("TestInline should have a group", group)
        assertTrue(group!!.contains(TestoClasses.TEST_INLINE))
    }

    fun testGetAttributeGroup_benchHasGroup() {
        val group = PsiUtil.getAttributeGroup(TestoClasses.BENCH)
        assertNotNull("Bench should have a group", group)
        assertTrue(group!!.contains(TestoClasses.BENCH))
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

    fun testGetAttributeGroup_allDataAttributesShareSameGroup() {
        val groups = TestoClasses.DATA_ATTRIBUTES.map { PsiUtil.getAttributeGroup(it) }
        val first = groups.first()
        for (group in groups) {
            assertSame("All data attributes should share the same group instance", first, group)
        }
    }

    fun testGetAttributeGroup_inlineAndBenchAreSeparateGroups() {
        val inlineGroup = PsiUtil.getAttributeGroup(TestoClasses.TEST_INLINE)
        val benchGroup = PsiUtil.getAttributeGroup(TestoClasses.BENCH)
        assertNotNull(inlineGroup)
        assertNotNull(benchGroup)
        assertFalse(
            "Inline and bench groups should not overlap",
            inlineGroup!!.toSet().intersect(benchGroup!!.toSet()).isNotEmpty()
        )
    }

    fun testAttributeGroups_allExplicitGroupsAreInMeaningful() {
        val meaningful = PsiUtil.MEANINGFUL_ATTRIBUTES.toSet()
        for (group in PsiUtil.ATTRIBUTE_GROUPS) {
            for (attr in group) {
                assertTrue("Grouped attribute $attr should be in MEANINGFUL_ATTRIBUTES", meaningful.contains(attr))
            }
        }
    }

    fun testAttributeGroups_noOverlapBetweenExplicitGroups() {
        val groups = PsiUtil.ATTRIBUTE_GROUPS
        for (i in groups.indices) {
            for (j in i + 1 until groups.size) {
                val overlap = groups[i].toSet().intersect(groups[j].toSet())
                assertTrue("Groups $i and $j should not overlap, but share: $overlap", overlap.isEmpty())
            }
        }
    }
}
