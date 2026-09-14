package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.tests.run.TestoRunPaths.PathResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TestoRunPathsTest {

    private fun relative(target: String, workingDirectory: String): String {
        val resolution = TestoRunPaths.relativePath(target, workingDirectory)
        assertTrue("expected Relative, got $resolution", resolution is PathResolution.Relative)
        return (resolution as PathResolution.Relative).path
    }

    @Test
    fun windowsPairRelativizes() {
        assertEquals("tests/FooTest.php", relative("D:\\repo\\app\\tests\\FooTest.php", "D:\\repo\\app"))
    }

    @Test
    fun systemIndependentPairRelativizes() {
        assertEquals("tests/FooTest.php", relative("D:/repo/app/tests/FooTest.php", "D:/repo/app"))
    }

    @Test
    fun windowsDriveLetterIsCaseInsensitive() {
        assertEquals("tests/FooTest.php", relative("d:/Repo/App/tests/FooTest.php", "D:/repo/app"))
    }

    @Test
    fun uncSameDistroRelativizes() {
        assertEquals(
            "tests/FooTest.php",
            relative(
                "//wsl.localhost/Ubuntu/home/user/project/app/tests/FooTest.php",
                "//wsl.localhost/Ubuntu/home/user/project/app",
            ),
        )
    }

    @Test
    fun uncAndPlainLinuxMixRelativizes_uncTarget() {
        assertEquals(
            "tests/FooTest.php",
            relative(
                "//wsl.localhost/Ubuntu/home/user/project/app/tests/FooTest.php",
                "/home/user/project/app",
            ),
        )
    }

    @Test
    fun uncAndPlainLinuxMixRelativizes_linuxTarget() {
        assertEquals(
            "tests/FooTest.php",
            relative(
                "/home/user/project/app/tests/FooTest.php",
                "//wsl.localhost/Ubuntu/home/user/project/app",
            ),
        )
    }

    @Test
    fun wslDollarFormRelativizes() {
        assertEquals(
            "tests/FooTest.php",
            relative(
                "//wsl\$/Ubuntu/home/user/project/app/tests/FooTest.php",
                "//wsl.localhost/Ubuntu/home/user/project/app",
            ),
        )
    }

    @Test
    fun backslashUncRelativizes() {
        assertEquals(
            "tests/FooTest.php",
            relative(
                "\\\\wsl.localhost\\Ubuntu\\home\\user\\project\\app\\tests\\FooTest.php",
                "\\\\wsl.localhost\\Ubuntu\\home\\user\\project\\app",
            ),
        )
    }

    @Test
    fun differentDistrosAreUnrelated() {
        assertSame(
            PathResolution.Unrelated,
            TestoRunPaths.relativePath(
                "//wsl.localhost/Ubuntu/home/x/tests/FooTest.php",
                "//wsl.localhost/Debian/home/x",
            ),
        )
    }

    @Test
    fun sameDistroCaseInsensitiveRelativizes() {
        assertEquals(
            "tests/FooTest.php",
            relative(
                "//wsl.localhost/UBUNTU/home/x/tests/FooTest.php",
                "//wsl.localhost/ubuntu/home/x",
            ),
        )
    }

    @Test
    fun linuxPathIsCaseSensitive() {
        assertSame(
            PathResolution.Unrelated,
            TestoRunPaths.relativePath("/home/User/app/tests/FooTest.php", "/home/user/app"),
        )
    }

    @Test
    fun outsideWorkingDirectoryIsUnrelated() {
        assertSame(
            PathResolution.Unrelated,
            TestoRunPaths.relativePath("/other/tests/FooTest.php", "/repo/app"),
        )
    }

    @Test
    fun ancestorDirectoryIsAncestor() {
        assertSame(
            PathResolution.Ancestor,
            TestoRunPaths.relativePath("/repo", "/repo/app"),
        )
    }

    @Test
    fun ancestorUncAndPlainLinuxMixIsAncestor() {
        assertSame(
            PathResolution.Ancestor,
            TestoRunPaths.relativePath(
                "//wsl.localhost/Ubuntu/home/user/project",
                "/home/user/project/app",
            ),
        )
    }

    @Test
    fun ancestorInDifferentDistroIsUnrelated() {
        assertSame(
            PathResolution.Unrelated,
            TestoRunPaths.relativePath(
                "//wsl.localhost/Ubuntu/home/user/project",
                "//wsl.localhost/Debian/home/user/project/app",
            ),
        )
    }

    @Test
    fun siblingOutsideIsUnrelated() {
        assertSame(
            PathResolution.Unrelated,
            TestoRunPaths.relativePath("/repo/tests/FooTest.php", "/repo/app"),
        )
    }

    @Test
    fun equalPathIsWorkingDirectory() {
        assertSame(
            PathResolution.WorkingDirectory,
            TestoRunPaths.relativePath("/repo/app", "/repo/app"),
        )
    }

    @Test
    fun trailingSlashesAreIgnored() {
        assertEquals("tests/FooTest.php", relative("/repo/app/tests/FooTest.php/", "/repo/app/"))
        assertSame(
            PathResolution.WorkingDirectory,
            TestoRunPaths.relativePath("/repo/app/", "/repo/app"),
        )
    }

    @Test
    fun nestedSubdirectoryApp() {
        assertEquals("tests/FooTest.php", relative("/repo/app/tests/FooTest.php", "/repo/app"))
    }

    @Test
    fun nestedAppMustNotUseProjectRoot() {
        assertEquals("app/tests/FooTest.php", relative("/repo/app/tests/FooTest.php", "/repo"))
    }

    @Test
    fun directoryTargetRelativizes() {
        assertEquals("tests/Feature", relative("/repo/app/tests/Feature", "/repo/app"))
    }

    @Test
    fun emptyInputsAreUnrelated() {
        assertSame(PathResolution.Unrelated, TestoRunPaths.relativePath("", "/repo/app"))
        assertSame(PathResolution.Unrelated, TestoRunPaths.relativePath("/repo/app/tests/Foo.php", ""))
    }
}
