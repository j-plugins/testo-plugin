package com.github.xepozz.testo.launch

import com.intellij.execution.ExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TestoCommandLineTest {

    @Test
    fun selectionArgumentsComeInAFixedOrder() {
        val selection = TestoRunSelection(
            testoType = "bench",
            suites = listOf("Unit", "My Suite"),
            groups = listOf("db"),
            excludeGroups = listOf("slow", "!flaky"),
            rerunFilters = listOf("\\App\\CalcTest::adds"),
        )

        assertEquals(
            listOf(
                "--type", "bench",
                "--suite", "Unit", "--suite", "My Suite",
                "--group", "db",
                "--group", "!slow", "--group", "!flaky",
                "--filter", "\\App\\CalcTest::adds",
            ),
            TestoCommandLine.selectionArguments(selection),
        )
    }

    @Test
    fun anEmptySelectionAddsNothing() {
        assertEquals(emptyList<String>(), TestoCommandLine.selectionArguments(TestoRunSelection()))
    }

    @Test
    fun methodRunSplitsTheDataProviderOffTheSelector() {
        assertEquals(
            listOf("--path", "tests/CalcTest.php", "--filter", "testSum", "--data-provider", "provider"),
            TestoCommandLine.methodArguments("/work/app/tests/CalcTest.php", "testSum#provider", "/work/app"),
        )
        assertEquals(
            listOf("--path", "tests/CalcTest.php", "--filter", "med:3:0"),
            TestoCommandLine.methodArguments("/work/app/tests/CalcTest.php", "med:3:0", "/work/app"),
        )
    }

    @Test
    fun theWorkingDirectoryItselfNeedsNoPathOnlyAsADirectory() {
        assertEquals(emptyList<String>(), TestoCommandLine.directoryArguments("/work/app", "/work/app"))
        assertThrows(ExecutionException::class.java) { TestoCommandLine.fileArguments("/work/app", "/work/app") }
    }

    @Test
    fun aFileOutsideTheWorkingDirectoryStopsTheRun() {
        assertThrows(ExecutionException::class.java) {
            TestoCommandLine.fileArguments("/elsewhere/tests/CalcTest.php", "/work/app")
        }
    }

    @Test
    fun emptyPathsAndTypes() {
        assertEquals(emptyList<String>(), TestoCommandLine.fileArguments("", "/work/app"))
        assertEquals(emptyList<String>(), TestoCommandLine.methodArguments("", "adds", "/work/app"))
        assertEquals(listOf("--suite", "Unit"), TestoCommandLine.typeArguments("Unit"))
    }
}
