package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.inspections.TestoGroupNameInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

/** The group-name inspection finds the problems PhpStorm's does, on the same code, and a noinspection comment holds. */
class TestoGroupNameInspectionTest : BasePlatformTestCase() {
    fun testTheSameProblemsAsOnPhpStorm() {
        myFixture.enableInspections(TestoGroupNameInspection())
        val file = myFixture.configureByText(
            "GroupsTest.php",
            """
            <?php
            namespace App;

            final class GroupsTest
            {
                #[\Testo\Filter\Group(' padded ')]
                public function testA(): void {}

                /** @noinspection TestoGroupNameInspection */
                #[\Testo\Filter\Group('!excluded')]
                public function testB(): void {}

                #[\Testo\Filter\Group]
                public function testC(): void {}
            }
            """.trimIndent(),
        )

        val problems = myFixture.doHighlighting()
            .filter { it.inspectionToolId == "TestoGroupNameInspection" }
            .map { "problem ${it.severity} '${file.text.substring(it.startOffset, it.endOffset)}': ${it.description}" }
        val onPhpStorm = Files.readAllLines(Path.of("src/test/testData/baseline/group-name-inspection.txt"))
            .filter { it.startsWith("problem ") }

        assertEquals(onPhpStorm, problems)
    }
}
