package com.github.xepozz.testo

import com.github.xepozz.testo.tests.inspections.groupNameProblemKey
import junit.framework.TestCase

class TestoGroupNameInspectionTest : TestCase() {
    fun testCleanNameHasNoProblem() {
        assertNull(groupNameProblemKey("db"))
        assertNull(groupNameProblemKey("db-slow"))
        assertNull(groupNameProblemKey("db slow"))
    }

    fun testBlankName() {
        assertEquals("inspection.group.name.blank", groupNameProblemKey(""))
        assertEquals("inspection.group.name.blank", groupNameProblemKey("   "))
    }

    fun testPaddedName() {
        assertEquals("inspection.group.name.whitespace", groupNameProblemKey(" db"))
        assertEquals("inspection.group.name.whitespace", groupNameProblemKey("db "))
    }

    fun testExclusionPrefixedName() {
        assertEquals("inspection.group.name.exclusion", groupNameProblemKey("!slow"))
    }

    fun testNameWithComma() {
        assertEquals("inspection.group.name.comma", groupNameProblemKey("a,b"))
    }

    fun testEveryProblemKeyExistsInTheBundle() {
        for (name in listOf("", " db", "!slow", "a,b")) {
            val key = groupNameProblemKey(name)!!
            val message = TestoBundle.message(key)
            assertFalse("Key '$key' must resolve to a real message", message.startsWith("!"))
        }
    }
}
