package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunConfigurationHandler
import com.github.xepozz.testo.tests.run.TestoRunConfigurationSettings
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import junit.framework.TestCase

class TestoRunConfigurationHandlerTest : TestCase() {

    fun testGetConfigFileOption() {
        assertEquals("--config", TestoRunConfigurationHandler.INSTANCE.configFileOption)
    }

    fun testSingletonInstance() {
        assertSame(TestoRunConfigurationHandler.INSTANCE, TestoRunConfigurationHandler.INSTANCE)
    }

    // ---- parseMethodName ----

    fun testParseMethodName_simpleMethod() {
        val parsed = TestoRunConfigurationHandler.INSTANCE.parseMethodName("testSomething")
        assertEquals("testSomething", parsed.method)
        assertEquals("", parsed.dataProvider)
    }

    fun testParseMethodName_withDataProvider() {
        val parsed = TestoRunConfigurationHandler.INSTANCE.parseMethodName("testSomething#provideData")
        assertEquals("testSomething", parsed.method)
        assertEquals("provideData", parsed.dataProvider)
    }

    fun testParseMethodName_withDataProviderAndIndex() {
        val parsed = TestoRunConfigurationHandler.INSTANCE.parseMethodName("testSomething#0:2")
        assertEquals("testSomething", parsed.method)
        assertEquals("0:2", parsed.dataProvider)
    }

    fun testParseMethodName_emptyString() {
        val parsed = TestoRunConfigurationHandler.INSTANCE.parseMethodName("")
        assertEquals("", parsed.method)
        assertEquals("", parsed.dataProvider)
    }

    // ---- prepareArguments ----

    fun testPrepareArguments_allDefaults_emptyList() {
        val settings = TestoRunConfigurationSettings()
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertTrue("No arguments should be added for default settings", arguments.isEmpty())
    }

    fun testPrepareArguments_withTestoType_bench() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.testoType = "bench"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--type", arguments[0])
        assertEquals("bench", arguments[1])
    }

    fun testPrepareArguments_withTestoType_test() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.testoType = "test"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--type", arguments[0])
        assertEquals("test", arguments[1])
    }

    fun testPrepareArguments_withTestoType_inline() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.testoType = "inline"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--type", arguments[0])
        assertEquals("inline", arguments[1])
    }

    fun testPrepareArguments_withTestoType_empty_skipped() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.testoType = ""
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertTrue("Empty testoType should not add arguments", arguments.isEmpty())
    }

    fun testPrepareArguments_defaultTestoType_noTypeFlag() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.suite = "unit"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--suite", arguments[0])
        assertEquals("unit", arguments[1])
        assertFalse("No --type flag when testoType is default", arguments.contains("--type"))
    }

    fun testPrepareArguments_withSuite() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.suite = "unit"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--suite", arguments[0])
        assertEquals("unit", arguments[1])
    }

    fun testPrepareArguments_withGroup() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.group = "fast"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--group", arguments[0])
        assertEquals("fast", arguments[1])
    }

    fun testPrepareArguments_withExcludeGroup() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.excludeGroup = "slow"
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--exclude-group", arguments[0])
        assertEquals("slow", arguments[1])
    }

    fun testPrepareArguments_withRepeat() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.repeat = 3
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--repeat", arguments[0])
        assertEquals("3", arguments[1])
    }

    fun testPrepareArguments_withParallel() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.parallel = 8
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(2, arguments.size)
        assertEquals("--parallel", arguments[0])
        assertEquals("8", arguments[1])
    }

    fun testPrepareArguments_zeroRepeatAndParallel_skipped() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.repeat = 0
        settings.runnerSettings.parallel = 0
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertTrue("Zero repeat/parallel should not add arguments", arguments.isEmpty())
    }

    fun testPrepareArguments_allOptions() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.testoType = "bench"
        settings.runnerSettings.suite = "integration"
        settings.runnerSettings.group = "db"
        settings.runnerSettings.excludeGroup = "slow"
        settings.runnerSettings.repeat = 2
        settings.runnerSettings.parallel = 4
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        assertEquals(12, arguments.size)
        assertTrue(arguments.contains("--type"))
        assertTrue(arguments.contains("bench"))
        assertTrue(arguments.contains("--suite"))
        assertTrue(arguments.contains("integration"))
        assertTrue(arguments.contains("--group"))
        assertTrue(arguments.contains("db"))
        assertTrue(arguments.contains("--exclude-group"))
        assertTrue(arguments.contains("slow"))
        assertTrue(arguments.contains("--repeat"))
        assertTrue(arguments.contains("2"))
        assertTrue(arguments.contains("--parallel"))
        assertTrue(arguments.contains("4"))
    }

    fun testPrepareArguments_orderIsCorrect() {
        val settings = TestoRunConfigurationSettings()
        settings.runnerSettings.testoType = "bench"
        settings.runnerSettings.suite = "unit"
        settings.runnerSettings.group = "fast"
        settings.runnerSettings.parallel = 2
        val arguments = mutableListOf<String?>()

        TestoRunConfigurationHandler.INSTANCE.prepareArguments(arguments, settings)

        // type comes first, then suite, then group, then parallel
        assertEquals("--type", arguments[0])
        assertEquals("bench", arguments[1])
        assertEquals("--suite", arguments[2])
        assertEquals("unit", arguments[3])
        assertEquals("--group", arguments[4])
        assertEquals("fast", arguments[5])
        assertEquals("--parallel", arguments[6])
        assertEquals("2", arguments[7])
    }
}
