package com.github.xepozz.testo.phpstorm

import com.github.xepozz.testo.phpstorm.tests.inspections.TestoGroupNameInspection
import junit.framework.TestCase

class TestoGroupNameInspectionTest : TestCase() {
    /**
     * `PhpInspection.getShortName()` returns the plain class simple name — it keeps the `Inspection` suffix instead of
     * trimming it the way `InspectionProfileEntry` does. A `shortName` in testo-php.xml that disagrees with it makes
     * the platform throw while writing the inspection profile, so pin the two together.
     */
    fun testRegisteredShortNameMatchesTheToolShortName() {
        val descriptor = javaClass.classLoader.getResource("META-INF/testo-php.xml")!!.readText()
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
}
