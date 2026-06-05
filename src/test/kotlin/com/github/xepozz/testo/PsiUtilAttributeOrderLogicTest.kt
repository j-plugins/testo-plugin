package com.github.xepozz.testo

import com.github.xepozz.testo.util.PsiUtil
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Additional pure-logic coverage of PsiUtil.getAttributeGroup beyond the existing
 * PsiUtilTest (which is junit.framework based). These cover every DATA member and
 * degenerate inputs.
 */
class PsiUtilAttributeOrderLogicTest {

    @Test
    fun testEveryDataMemberMapsToDataGroup() {
        for (fqn in TestoClasses.DATA_ATTRIBUTES) {
            assertSame("$fqn should map to DATA group", TestoClasses.DATA_ATTRIBUTES, PsiUtil.getAttributeGroup(fqn))
        }
    }

    @Test
    fun testInlineMemberMapsToInlineGroup() {
        assertSame(TestoClasses.TEST_INLINE_ATTRIBUTES, PsiUtil.getAttributeGroup(TestoClasses.TEST_INLINE))
    }

    @Test
    fun testBenchMemberMapsToBenchGroup() {
        assertSame(TestoClasses.BENCH_ATTRIBUTES, PsiUtil.getAttributeGroup(TestoClasses.BENCH))
    }

    @Test
    fun testPlainTestHasNoGroup() {
        assertNull(PsiUtil.getAttributeGroup(TestoClasses.TEST))
    }

    @Test
    fun testBlankAndNullReturnNull() {
        assertNull(PsiUtil.getAttributeGroup(null))
        assertNull(PsiUtil.getAttributeGroup(""))
        assertNull(PsiUtil.getAttributeGroup("   "))
        assertNull(PsiUtil.getAttributeGroup("\\Testo\\Unknown"))
    }

    @Test
    fun testAttributeGroupsArrayShape() {
        // 3 groups: DATA, TEST_INLINE, BENCH — and they are the exact source arrays
        assertSame(TestoClasses.DATA_ATTRIBUTES, PsiUtil.ATTRIBUTE_GROUPS[0])
        assertSame(TestoClasses.TEST_INLINE_ATTRIBUTES, PsiUtil.ATTRIBUTE_GROUPS[1])
        assertSame(TestoClasses.BENCH_ATTRIBUTES, PsiUtil.ATTRIBUTE_GROUPS[2])
    }
}
