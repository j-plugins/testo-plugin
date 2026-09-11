package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunPaths
import junit.framework.TestCase

class TestoRunPathsTest : TestCase() {

    // ---- resolveWorkingDirectory ----

    fun testResolveWorkingDirectory_customWinsOverConfigFile() {
        assertEquals(
            "/custom/wd",
            TestoRunPaths.resolveWorkingDirectory(
                customWorkingDirectory = "/custom/wd",
                configurationFilePath = "/repo/app/testo.php",
                fallback = { "/repo" },
            ),
        )
    }

    fun testResolveWorkingDirectory_configFileParentWhenNoCustom() {
        assertEquals(
            "/repo/app",
            TestoRunPaths.resolveWorkingDirectory(
                customWorkingDirectory = null,
                configurationFilePath = "/repo/app/testo.php",
                fallback = { "/repo" },
            ),
        )
    }

    fun testResolveWorkingDirectory_alternativeConfigFileParent() {
        assertEquals(
            "/repo/custom",
            TestoRunPaths.resolveWorkingDirectory(
                customWorkingDirectory = "",
                configurationFilePath = "/repo/custom/testo.php",
                fallback = { "/repo" },
            ),
        )
    }

    fun testResolveWorkingDirectory_fallbackWhenNoConfigFile() {
        assertEquals(
            "/repo",
            TestoRunPaths.resolveWorkingDirectory(
                customWorkingDirectory = null,
                configurationFilePath = null,
                fallback = { "/repo" },
            ),
        )
    }

    fun testResolveWorkingDirectory_flatProject_configAtRoot() {
        assertEquals(
            "/project",
            TestoRunPaths.resolveWorkingDirectory(
                customWorkingDirectory = null,
                configurationFilePath = "/project/testo.php",
                fallback = { "/project" },
            ),
        )
    }

    fun testResolveWorkingDirectory_fallbackNotCalledWhenConfigPresent() {
        var fallbackCalled = false
        TestoRunPaths.resolveWorkingDirectory(
            customWorkingDirectory = null,
            configurationFilePath = "/repo/app/testo.php",
            fallback = {
                fallbackCalled = true
                "/repo"
            },
        )
        assertFalse(fallbackCalled)
    }

    // ---- relativePath: scenario A (flat project) ----

    fun testRelativePath_flatProjectFile() {
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath("/project/tests/FooTest.php", "/project"),
        )
    }

    // ---- relativePath: scenario B (PHP project in subdirectory) ----

    fun testRelativePath_nestedPhpProjectFile() {
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath("/repo/app/tests/FooTest.php", "/repo/app"),
        )
    }

    fun testRelativePath_nestedMustNotUsePhpStormProjectRoot() {
        // Relativizing against the PhpStorm project root would keep an "app/" prefix — wrong for Testo.
        assertEquals(
            "app/tests/FooTest.php",
            TestoRunPaths.relativePath("/repo/app/tests/FooTest.php", "/repo"),
        )
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath("/repo/app/tests/FooTest.php", "/repo/app"),
        )
    }

    // ---- relativePath: scenario C (WSL UNC) ----

    fun testRelativePath_wslUncPaths() {
        val wd = "//wsl.localhost/SomeDistro/home/user/project/app"
        val file = "//wsl.localhost/SomeDistro/home/user/project/app/tests/FooTest.php"
        assertEquals("tests/FooTest.php", TestoRunPaths.relativePath(file, wd))
    }

    fun testRelativePath_mixedWslUncAndLinuxPath() {
        // PhpStorm often stores the config/WD as WSL UNC and the test file as a plain Linux path (or the reverse).
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath(
                "/home/user/project/app/tests/FooTest.php",
                "//wsl.localhost/SomeDistro/home/user/project/app",
            ),
        )
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath(
                "//wsl.localhost/SomeDistro/home/user/project/app/tests/FooTest.php",
                "/home/user/project/app",
            ),
        )
    }

    fun testRelativePath_wslDollarUnc() {
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath(
                "//wsl\$/SomeDistro/home/user/project/app/tests/FooTest.php",
                "//wsl.localhost/SomeDistro/home/user/project/app",
            ),
        )
    }

    fun testCanonicalize_stripsWslUncPrefix() {
        assertEquals(
            "/home/user/project/app",
            TestoRunPaths.canonicalize("//wsl.localhost/SomeDistro/home/user/project/app"),
        )
        assertEquals(
            "/home/user/project/app",
            TestoRunPaths.canonicalize("/home/user/project/app"),
        )
    }

    // ---- relativePath: scenario D (directory) ----

    fun testRelativePath_directory() {
        assertEquals(
            "tests/Feature",
            TestoRunPaths.relativePath("/repo/app/tests/Feature", "/repo/app"),
        )
    }

    // ---- relativePath: scenario E (method — same as file; never empty) ----

    fun testRelativePath_methodFileIsNonEmpty() {
        val relative = TestoRunPaths.relativePath(
            "/repo/app/tests/Feature/FooTest.php",
            "/repo/app",
        )
        assertEquals("tests/Feature/FooTest.php", relative)
        assertFalse(relative.isNullOrEmpty())
    }

    fun testRelativePath_windowsStyleSeparatorsNormalizedToSlash() {
        assertEquals(
            "tests/FooTest.php",
            TestoRunPaths.relativePath("C:/repo/app/tests/FooTest.php", "C:/repo/app"),
        )
    }

    fun testRelativePath_outsideWorkingDirectory_returnsNull() {
        assertNull(
            TestoRunPaths.relativePath("/other/tests/FooTest.php", "/repo/app"),
        )
    }

    fun testRelativePath_sameAsWorkingDirectory_returnsNull() {
        // Running the Testo root itself needs no --path.
        assertNull(TestoRunPaths.relativePath("/repo/app", "/repo/app"))
    }

    fun testRelativePath_emptyInputs_returnNull() {
        assertNull(TestoRunPaths.relativePath("", "/repo/app"))
        assertNull(TestoRunPaths.relativePath("/repo/app/tests/Foo.php", ""))
    }

    fun testParentOfConfigurationFile() {
        assertEquals("/repo/app", TestoRunPaths.parentOfConfigurationFile("/repo/app/testo.php"))
        assertNull(TestoRunPaths.parentOfConfigurationFile(null))
        assertNull(TestoRunPaths.parentOfConfigurationFile(""))
    }

    fun testParentOfConfigurationFile_preservesWslUncForm() {
        assertEquals(
            "//wsl.localhost/SomeDistro/home/user/project/app",
            TestoRunPaths.parentOfConfigurationFile(
                "//wsl.localhost/SomeDistro/home/user/project/app/testo.php",
            ),
        )
    }
}
