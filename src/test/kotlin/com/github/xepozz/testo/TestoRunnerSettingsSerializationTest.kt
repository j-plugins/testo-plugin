package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import junit.framework.TestCase
import org.jdom.output.XMLOutputter

/**
 * The persisted shape of the group fields. Saved run configurations live in `.idea/workspace.xml` (or in a
 * per-configuration file under `.idea/runConfigurations`), so a change here silently invalidates what users
 * already have.
 */
class TestoRunnerSettingsSerializationTest : TestCase() {

    /** Mirrors how the platform saves a run configuration: defaults are skipped, so unset fields leave no trace. */
    private fun serialize(settings: TestoRunnerSettings): String =
        XMLOutputter().outputString(XmlSerializer.serialize(settings, SkipDefaultsSerializationFilter()))

    private fun deserialize(xml: String): TestoRunnerSettings =
        XmlSerializer.deserialize(org.jdom.input.SAXBuilder().build(xml.reader()).rootElement, TestoRunnerSettings::class.java)

    fun testGroupsAreWrittenAsAList() {
        val settings = TestoRunnerSettings().apply {
            groups = mutableListOf("db", "slow")
            excludeGroups = mutableListOf("flaky")
        }

        val xml = serialize(settings)

        assertTrue("groups element expected, got: $xml", xml.contains("<groups>"))
        assertTrue(xml.contains("""<option value="db" />"""))
        assertTrue(xml.contains("""<option value="slow" />"""))
        assertTrue(xml.contains("<exclude_groups>"))
        assertTrue(xml.contains("""<option value="flaky" />"""))
        // The legacy attribute must not come back once a configuration is saved in the new shape.
        assertFalse("legacy group attribute must not be written: $xml", xml.contains("group=\""))
    }

    fun testEmptyGroupsWriteNothing() {
        val xml = serialize(TestoRunnerSettings())

        assertFalse("Empty lists are the default and must stay out of the XML: $xml", xml.contains("groups"))
    }

    fun testGroupsRoundTrip() {
        val settings = TestoRunnerSettings().apply {
            groups = mutableListOf("db", "a,b")
            excludeGroups = mutableListOf("slow")
        }

        val restored = deserialize(serialize(settings))

        // A comma inside a name survives, because it is no longer a separator anywhere in the model.
        assertEquals(listOf("db", "a,b"), restored.groups)
        assertEquals(listOf("slow"), restored.excludeGroups)
    }

    fun testLegacyCommaSeparatedAttributeIsMigrated() {
        val restored = deserialize(
            """<TestoRunnerSettings group="db,slow" exclude_group="flaky" />"""
        )

        assertEquals("The pre-list attribute is still readable", "db,slow", restored.legacyGroup)

        restored.migrateLegacyNames()

        assertEquals(listOf("db", "slow"), restored.groups)
        assertEquals(listOf("flaky"), restored.excludeGroups)
        assertEquals("Migration clears the legacy field so it is not written back", "", restored.legacyGroup)
        assertEquals("", restored.legacyExcludeGroup)
    }

    /** A suite name is never split: unlike the group field it never had a separator, and may hold anything. */
    fun testLegacySingleSuiteBecomesAOneItemList() {
        val restored = deserialize("""<TestoRunnerSettings suite="Unit, slow" />""")

        restored.migrateLegacyNames()

        assertEquals(listOf("Unit, slow"), restored.suites)
        assertEquals("", restored.legacySuite)
        assertFalse("The legacy attribute is gone after a save", serialize(restored).contains("suite=\""))
    }

    fun testMigrationIsIdempotentAndKeepsExistingLists() {
        val settings = TestoRunnerSettings().apply { groups = mutableListOf("db") }

        settings.migrateLegacyNames()
        settings.migrateLegacyNames()

        assertEquals(listOf("db"), settings.groups)
    }

    fun testMigratedConfigurationSerializesAsAList() {
        val restored = deserialize("""<TestoRunnerSettings group="db,slow" />""")
        restored.migrateLegacyNames()

        val xml = serialize(restored)

        assertTrue(xml.contains("<groups>"))
        assertFalse("The legacy attribute is gone after a save: $xml", xml.contains("group=\""))
    }

    /** A configuration saved before the field existed reads back with the default, benchmarks excluded and all. */
    fun testCoverageOptionsDefaultSurvivesAnOlderConfiguration() {
        val restored = deserialize("""<TestoRunnerSettings />""")

        assertEquals("--type=!bench", restored.coverageOptions)
        assertEquals("auto", restored.coverageLevel)
        assertFalse("The defaults must stay out of the XML", serialize(restored).contains("coverage_"))
    }

    fun testCoverageOptionsRoundTrip() {
        val settings = TestoRunnerSettings(coverageLevel = "branch", coverageOptions = "--filter x")

        val restored = deserialize(serialize(settings))

        assertEquals("branch", restored.coverageLevel)
        assertEquals("--filter x", restored.coverageOptions)
    }

    fun testUnknownElementsDoNotBreakDeserialization() {
        // Forward compatibility: an XML written by a newer plugin must not blow up the older one.
        val restored = deserialize("""<TestoRunnerSettings><whatever /></TestoRunnerSettings>""")

        assertTrue(restored.groups.isEmpty())
    }

    fun testParseNames() {
        assertEquals(listOf("db", "slow"), TestoRunnerSettings.parseNames(" db , slow "))
        assertTrue(TestoRunnerSettings.parseNames(" , , ").isEmpty())
        assertTrue(TestoRunnerSettings.parseNames("").isEmpty())
    }

    fun testFormatNames() {
        assertEquals("db, slow", TestoRunnerSettings.formatNames(listOf("db", "slow")))
        assertEquals("", TestoRunnerSettings.formatNames(emptyList()))
    }
}
