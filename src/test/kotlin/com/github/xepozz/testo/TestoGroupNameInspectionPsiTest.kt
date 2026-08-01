package com.github.xepozz.testo

import com.github.xepozz.testo.tests.inspections.TestoGroupNameInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType

class TestoGroupNameInspectionPsiTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(TestoGroupNameInspection())
    }

    fun testSuspiciousNamesAreHighlighted() {
        myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class OrderTest {
                #[\Testo\Filter\Group(
                    <warning descr="Group name contains a comma — the separator of the run configuration Group field; consider renaming">'a,b'</warning>,
                    <warning descr="Group name starts with \"!\" — the command line reads it as an exclusion, so the group cannot be selected with --group">'!slow'</warning>,
                    <warning descr="Group name is blank">''</warning>,
                    <warning descr="Group name has leading or trailing whitespace">' db'</warning>,
                    'clean'
                )]
                public function testOrder(): void {}
            }"""
        )

        myFixture.checkHighlighting(true, false, false)
    }

    fun testGroupWithoutNamesIsHighlighted() {
        myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class OrderTest {
                #[<warning descr="#[Group] without names selects nothing">\Testo\Filter\Group</warning>]
                public function testOrder(): void {}
            }"""
        )

        myFixture.checkHighlighting(true, false, false)
    }

    fun testConstantArgumentsAreNotJudged() {
        myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class OrderTest {
                #[\Testo\Filter\Group(SomeClass::GROUP)]
                public function testOrder(): void {}
            }"""
        )

        myFixture.checkHighlighting(true, false, false)
    }

    fun testOtherAttributesAreIgnored() {
        myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class OrderTest {
                #[\Testo\Test('a,b')]
                public function testOrder(): void {}
            }"""
        )

        myFixture.checkHighlighting(true, false, false)
    }
}
