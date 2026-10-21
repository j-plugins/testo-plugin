package com.github.xepozz.testo.openide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.inspections.PhpUnhandledExceptionInspection

/** PHP for OpenIDE's own unhandled-exception inspection leaves a Testo assertion alone, and nothing else. */
class TestoUnhandledExceptionSuppressionTest : BasePlatformTestCase() {
    fun testAnAssertionIsNotFlaggedButAnotherDocumentedThrowIs() {
        myFixture.enableInspections(PhpUnhandledExceptionInspection())
        myFixture.addFileToProject(
            "vendor/Assert.php",
            """
            <?php
            namespace Testo\Assert\State\Assertion { class AssertionException extends \Exception {} }
            namespace Testo {
                final class Assert
                {
                    /** @throws \Testo\Assert\State\Assertion\AssertionException */
                    public static function same(mixed ${'$'}a, mixed ${'$'}b): void {}
                }
                final class Io
                {
                    /** @throws \RuntimeException */
                    public static function read(): void {}
                }
            }
            """.trimIndent(),
        )
        val file = myFixture.configureByText(
            "ThingTest.php",
            """
            <?php
            namespace App;

            final class ThingTest
            {
                public function testThing(): void
                {
                    \Testo\Assert::same(1, 1);
                    \Testo\Io::read();
                }
            }
            """.trimIndent(),
        )

        val flagged = myFixture.doHighlighting()
            .filter { it.inspectionToolId == "PhpUnhandledException" }
            .map { file.text.substring(it.startOffset, it.endOffset) }

        assertEquals(listOf("read"), flagged)
    }
}
