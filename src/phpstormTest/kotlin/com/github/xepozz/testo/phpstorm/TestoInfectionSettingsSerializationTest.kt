package com.github.xepozz.testo.phpstorm

import com.github.xepozz.testo.phpstorm.tests.run.TestoRunnerSettings
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import junit.framework.TestCase
import org.jdom.input.SAXBuilder
import org.jdom.output.XMLOutputter

class TestoInfectionSettingsSerializationTest : TestCase() {
    private fun xml(settings: TestoRunnerSettings): String =
        XMLOutputter().outputString(XmlSerializer.serialize(settings, SkipDefaultsSerializationFilter()))

    fun testEveryInfectionOptionSurvivesSerializationAndCopying() {
        val settings = TestoRunnerSettings(
            infectionScope = "git-lines", infectionGitDiffBase = "release branch", infectionThreads = "4",
            infectionOnlyCoveringTestCases = true, infectionWithUncovered = true, infectionTimeoutsAsEscaped = true,
            infectionMutators = "@default", infectionStaticAnalysisTool = "phpstan", infectionOptions = "--debug",
            logJunit = false,
        )
        val serialized = xml(settings)
        val element = SAXBuilder().build(serialized.reader()).rootElement
        assertEquals(
            mapOf("infection_scope" to "git-lines", "infection_git_diff_base" to "release branch",
                "infection_threads" to "4", "infection_only_covering_test_cases" to "true",
                "infection_with_uncovered" to "true", "infection_timeouts_as_escaped" to "true",
                "infection_mutators" to "@default", "infection_static_analysis_tool" to "phpstan",
                "infection_options" to "--debug"),
            element.attributes.filter { it.name.startsWith("infection_") }.associate { it.name to it.value },
        )
        val restored = XmlSerializer.deserialize(element, TestoRunnerSettings::class.java)
        assertEquals(serialized, xml(restored))
        assertEquals(serialized, xml(TestoRunnerSettings.fromPhpTestRunnerSettings(settings)))
        val target = TestoRunnerSettings()
        target.copyInfectionFrom(restored)
        target.logJunit = false
        assertEquals(serialized, xml(target))
        target.infectionThreads = "8"
        assertEquals("4", settings.infectionThreads)
        assertTrue(restored.rerunFilters.isEmpty())
    }

    fun testAbsentInfectionFieldsAndExplicitJunitOptOutRemainCompatible() {
        val element = SAXBuilder().build("""<TestoRunnerSettings log_junit="false" group="db,slow" />""".reader()).rootElement
        val settings = XmlSerializer.deserialize(element, TestoRunnerSettings::class.java)
        settings.migrateLegacyNames()
        assertFalse(settings.logJunit)
        assertEquals(listOf("db", "slow"), settings.groups)
        assertEquals("covered", settings.infectionScope)
        assertFalse(xml(settings).contains("infection_"))
        assertTrue(TestoRunnerSettings().logJunit)
    }

    fun testTemporaryRerunFiltersAreNotSerialized() {
        val serialized = xml(TestoRunnerSettings().apply { rerunFilters = listOf("temporary") })
        assertFalse("Temporary filters must not be saved: $serialized", serialized.contains("temporary"))
    }
}
