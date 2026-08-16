package com.github.xepozz.testo

import com.github.xepozz.testo.index.TestoGroupsIndex
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType

/**
 * What the group index reads out of one file. `#[Group]` is variadic and may sit on a class, a method or a standalone
 * function, so a file contributes a set — the index has no per-declaration structure to keep.
 */
class TestoGroupsIndexPsiTest : BasePlatformTestCase() {

    fun testNamesAreCollectedFromEveryDeclarationAndDeduplicated() {
        val file = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Filter\Group('db')]
            class OrderTest {
                #[\Testo\Filter\Group('db', 'slow')]
                public function testOrder(): void {}
            }

            #[\Testo\Filter\Group('smoke')]
            function testStandalone(): void {}"""
        )

        assertEquals(setOf("db", "slow", "smoke"), TestoGroupsIndex.groupNamesIn(file))
    }

    fun testNonLiteralAndBlankNamesAreSkipped() {
        val file = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class OrderTest {
                #[\Testo\Filter\Group('', SOME_CONST, 'db')]
                public function testOrder(): void {}
            }"""
        )

        assertEquals(setOf("db"), TestoGroupsIndex.groupNamesIn(file))
    }

    fun testAnotherAttributeContributesNothing() {
        val file = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class OrderTest {
                #[\Testo\Test]
                public function testOrder(): void {}
            }"""
        )

        assertEmpty(TestoGroupsIndex.groupNamesIn(file))
    }
}
