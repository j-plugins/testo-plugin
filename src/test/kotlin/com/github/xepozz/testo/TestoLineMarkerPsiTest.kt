package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

class TestoLineMarkerPsiTest : BasePlatformTestCase() {

    fun testGetLocationHint_forClass() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        val hint = TestoTestRunLineMarkerProvider.getLocationHint(phpClass)

        assertTrue("Location hint should start with schema prefix", hint.startsWith("php_qn://"))
        assertTrue("Location hint should contain class FQN", hint.contains("\\UserTest"))
    }

    fun testGetLocationHint_forMethod() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val hint = TestoTestRunLineMarkerProvider.getLocationHint(method)

        assertTrue("Location hint should start with schema prefix", hint.startsWith("php_qn://"))
        assertTrue("Location hint should contain method name", hint.contains("::testSomething"))
    }

    fun testGetLocationHint_forFile() {
        val psiFile = myFixture.configureByText(
            "SomeTest.php",
            """<?php class SomeTest {}"""
        )
        val hint = TestoTestRunLineMarkerProvider.getLocationHint(psiFile)

        assertTrue("File location hint should start with schema prefix", hint.startsWith("php_qn://"))
    }

    fun testGetDataProviderLocationHint() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public static function provideData(): iterable { yield [1]; } }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val hint = TestoTestRunLineMarkerProvider.getDataProviderLocationHint(method)

        assertTrue("Data provider hint should start with schema prefix", hint.startsWith("php_qn://"))
        assertTrue("Data provider hint should contain method name", hint.contains("::provideData"))
    }

    fun testGetInlineTestLocationHint() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class FooTest { public function testBar(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val hint = TestoTestRunLineMarkerProvider.getInlineTestLocationHint(method, 0)

        assertTrue("Inline hint should contain index", hint.endsWith("#0"))
    }

    fun testGetInlineTestLocationHint_withDifferentIndex() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class FooTest { public function testBar(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        val hint = TestoTestRunLineMarkerProvider.getInlineTestLocationHint(method, 3)

        assertTrue("Inline hint should contain the specific index", hint.endsWith("#3"))
    }
}
