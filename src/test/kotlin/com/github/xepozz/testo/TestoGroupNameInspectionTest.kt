package com.github.xepozz.testo

import com.github.xepozz.testo.tests.inspections.TestoGroupNameInspection
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

    /**
     * `PhpInspection.getShortName()` returns the plain class simple name — it keeps the `Inspection` suffix instead of
     * trimming it the way `InspectionProfileEntry` does. A `shortName` in plugin.xml that disagrees with it makes the
     * platform throw while writing the inspection profile, so pin the two together.
     */
    fun testRegisteredShortNameMatchesTheToolShortName() {
        val descriptor = javaClass.classLoader.getResource("META-INF/plugin.xml")!!.readText()
        val shortName = Regex("""<localInspection\b[^>]*?shortName="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(descriptor)
            ?.groupValues
            ?.get(1)

        assertEquals(TestoGroupNameInspection::class.java.simpleName, shortName)
    }

    fun testDescriptionFileIsNamedAfterTheShortName() {
        val shortName = TestoGroupNameInspection::class.java.simpleName
        assertNotNull(
            "inspectionDescriptions/$shortName.html must exist, otherwise the settings page shows no description",
            javaClass.classLoader.getResource("inspectionDescriptions/$shortName.html"),
        )
    }

    fun testEveryProblemKeyExistsInTheBundle() {
        for (name in listOf("", " db", "!slow", "a,b")) {
            val key = groupNameProblemKey(name)!!
            val message = TestoBundle.message(key)
            assertFalse("Key '$key' must resolve to a real message", message.startsWith("!"))
        }
    }
}
