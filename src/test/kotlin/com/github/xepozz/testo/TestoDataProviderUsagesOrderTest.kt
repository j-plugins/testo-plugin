package com.github.xepozz.testo

import com.github.xepozz.testo.baseline.BaselineFixture
import com.github.xepozz.testo.index.TestoDataProviderUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TestoDataProviderUsagesOrderTest : BasePlatformTestCase() {
    fun testMethodsInOneFileKeepSourceOrderRegardlessOfInputOrder() {
        val file = myFixture.addFileToProject("tests/OrderingTest.php", """
            <?php
            class OrderingTest {
                public function zebra() {}
                public function alpha() {}
            }
        """.trimIndent())
        val methods = file.phpMethods().map { it.psi }
        assertEquals(2, methods.size)
        assertEquals(methods, TestoDataProviderUtils.inSourceOrder(methods.reversed()))
        assertEquals(methods, TestoDataProviderUtils.inSourceOrder(methods))
    }

    fun testFileOrderPrecedesOffsetsWithinFiles() {
        val later = myFixture.addFileToProject("tests/ZTest.php", "<?php class ZTest { public function first() {} }")
        val earlier = myFixture.addFileToProject("tests/ATest.php", "<?php class ATest { public function second() {} }")
        val expected = listOf(earlier.phpMethods().single().psi, later.phpMethods().single().psi)
        assertEquals(expected, TestoDataProviderUtils.inSourceOrder(expected.reversed()))
    }

    fun testSharedProviderLookupReturnsItsMethodsInSourceOrder() {
        val fixture = BaselineFixture(myFixture).also { it.setUp() }
        try {
            val functions = fixture.files.getValue("tests/SharedProviderTest.php").phpFunctions()
            val provider = functions.single { it.name == "shared" }.psi
            val expected = functions.filter { it.name == "first" || it.name == "second" }.map { it.psi }
            assertEquals(2, expected.size)
            assertEquals(expected, TestoDataProviderUtils.findDataProviderUsages(provider))
        } finally {
            fixture.tearDown()
        }
    }
}
