package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.run.testing.PhpTestFrameworks

/** Testo on the test framework page of PHP for OpenIDE, and nothing more than that from the framework SPI. */
class TestoFrameworkTypeTest : BasePlatformTestCase() {
    private fun composer(installed: Boolean) {
        myFixture.addFileToProject("composer.json", """{"require-dev": {"testo/testo": "^0.10"}}""")
        myFixture.addFileToProject(
            "composer.lock",
            """{"packages": [], "packages-dev": [{"name": "testo/testo", "version": "0.10.54"}]}""",
        )
        if (installed) myFixture.addFileToProject("vendor/bin/testo", "#!/usr/bin/env php\n")
    }

    fun testTestoIsAmongTheFrameworks() {
        val testo = PhpTestFrameworks.byId(TestoFrameworkType.ID)

        assertInstanceOf(testo, TestoFrameworkType::class.java)
        assertEquals("Testo", testo!!.displayName)
    }

    fun testTheBinaryAndVersionComeFromComposer() {
        composer(installed = true)
        val testo = TestoFrameworkType.instance()!!
        val root = project.guessProjectDir()!!.path

        assertEquals("$root/vendor/bin/testo", PhpTestFrameworks.binaryPath(project, testo))
        assertEquals("0.10.54", PhpTestFrameworks.knownVersion(project, testo))
        assertEquals("0.10.54", testo.versionProbe.parse("Testo 0.10.54\n"))
        assertNull("Not Testo's output", testo.versionProbe.parse("PHPUnit 11.0.0"))
    }

    fun testATestoClassGetsOnlyTestosRunIcon() {
        composer(installed = true)
        val file = myFixture.configureByText(
            "CalcTest.php",
            "<?php\nnamespace App;\n\nuse Testo\\Test;\n\nfinal class CalcTest\n{\n    #[Test]\n    public function adds(): void {}\n}\n",
        )
        fun iconsAt(anchor: String): List<String> {
            val leaf = file.findElementAt(file.text.indexOf(anchor))!!
            return RunLineMarkerContributor.EXTENSION.allForLanguageOrAny(file.language)
                .filter { it.getInfo(leaf) != null }
                .map { it.javaClass.simpleName }
        }

        assertEquals(listOf(TestoTestRunLineMarkerProvider::class.java.simpleName), iconsAt("CalcTest\n"))
        assertEquals(listOf(TestoTestRunLineMarkerProvider::class.java.simpleName), iconsAt("adds"))
    }
}
