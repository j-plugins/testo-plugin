package com.github.xepozz.testo.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestoConfigurationNamesTest {
    private val filterOnly = TestoRunSelection(scope = TestoScope.CONFIGURATION_FILE)

    @Test
    fun filterOnlyRunsAreNamedAfterWhatSelectsThem() {
        assertEquals("Group 'db'", TestoConfigurationNames.suggestedName(filterOnly.copy(groups = listOf("db"))))
        assertEquals("Groups 'db', 'slow'", TestoConfigurationNames.suggestedName(filterOnly.copy(groups = listOf("db", "slow"))))
        assertEquals("Suite 'Unit'", TestoConfigurationNames.suggestedName(filterOnly.copy(suites = listOf("Unit"))))
        assertEquals("Type 'bench'", TestoConfigurationNames.suggestedName(filterOnly.copy(testoType = "bench")))
        assertEquals("Group 'db'", TestoConfigurationNames.suggestedName(filterOnly.copy(groups = listOf("db"), suites = listOf("Unit"))))
    }

    @Test
    fun aQualifiedSelectorIsShortenedToItsTail() {
        val selection = TestoRunSelection(scope = TestoScope.METHOD, filePath = "/app/tests/Calculator.php", methodName = "\\Ns\\Calculator::med:3:0")

        assertEquals("Calculator.php::med:3:0", TestoConfigurationNames.suggestedName(selection))
        assertEquals("med:3:0", TestoConfigurationNames.qualifiedMethodTail(selection))
        assertNull(TestoConfigurationNames.qualifiedMethodTail(selection.copy(methodName = "adds")))
        assertNull(TestoConfigurationNames.suggestedName(selection.copy(methodName = "adds")))
    }

    @Test
    fun aFilterOnlyRunNeedsNoConfigurationFile() {
        assertTrue(TestoConfigurationNames.isFilterOnlyRun(filterOnly.copy(groups = listOf("db"))))
        assertTrue(TestoConfigurationNames.isFilterOnlyRun(filterOnly.copy(excludeGroups = listOf("slow"))))
        assertTrue(TestoConfigurationNames.isFilterOnlyRun(filterOnly.copy(suites = listOf("Unit"))))
        assertTrue(TestoConfigurationNames.isFilterOnlyRun(filterOnly.copy(testoType = "test")))
        assertTrue(TestoConfigurationNames.isFilterOnlyRun(filterOnly.copy(rerunFilters = listOf("\\A::b"))))
        assertFalse("Nothing selects it", TestoConfigurationNames.isFilterOnlyRun(filterOnly))
        assertFalse(
            "An explicit configuration file makes it a configuration run",
            TestoConfigurationNames.isFilterOnlyRun(filterOnly.copy(groups = listOf("db"), useAlternativeConfigurationFile = true)),
        )
        assertFalse(TestoConfigurationNames.isFilterOnlyRun(TestoRunSelection(scope = TestoScope.FILE, groups = listOf("db"))))
    }
}
