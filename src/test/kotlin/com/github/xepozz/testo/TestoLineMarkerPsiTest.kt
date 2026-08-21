package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpAttribute
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

    fun testGetInfo_groupAttributeWithoutNamesHasNoGutterIcon() {
        val leaf = groupAttributeNameLeaf(
            """<?php
            class OrderTest {
                #[\Testo\Test]
                #[\Testo\Filter\Group]
                public function persistsOrder(): void {}
            }"""
        )

        // The producer refuses an empty group, so an icon here would offer a run that does nothing.
        assertNull(TestoTestRunLineMarkerProvider().getInfo(leaf))
    }

    fun testGetInfo_groupAttributeWithNameHasGutterIcon() {
        val leaf = groupAttributeNameLeaf(
            """<?php
            class OrderTest {
                #[\Testo\Test]
                #[\Testo\Filter\Group('db')]
                public function persistsOrder(): void {}
            }"""
        )

        assertNotNull(TestoTestRunLineMarkerProvider().getInfo(leaf))
    }

    /** The identifier leaf of the `Group` class reference inside the `#[\Testo\Filter\Group(...)]` attribute. */
    private fun groupAttributeNameLeaf(text: String): PsiElement {
        val psiFile = myFixture.configureByText(PhpFileType.INSTANCE, text)
        val attribute = PsiTreeUtil.findChildrenOfType(psiFile, PhpAttribute::class.java)
            .first { it.fqn == TestoClasses.FILTER_GROUP }
        val reference = PsiTreeUtil.findChildOfType(attribute, ClassReference::class.java)!!
        return reference.lastChild
    }

    fun testGetInfo_inheritorOfAttributedBaseHasGutterIcons() {
        myFixture.addFileToProject(
            "BaseMailCase.php",
            """<?php #[\Testo\Test] abstract class BaseMailCase { public function delivers(): void {} }"""
        )
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php final class MailCase extends BaseMailCase { public function bounces(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!

        assertNotNull("The inheritor of a #[Test] base carries a class gutter", TestoTestRunLineMarkerProvider().getInfo(phpClass.nameIdentifier!!))
        assertNotNull("Its public methods carry method gutters", TestoTestRunLineMarkerProvider().getInfo(method.nameIdentifier!!))
    }

    fun testGetInfo_methodDeclaredInAttributedAbstractBaseHasGutterIcon() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php #[\Testo\Test] abstract class BaseFtpCase { public function uploads(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!

        assertNotNull("The declaration in the base carries the gutter", TestoTestRunLineMarkerProvider().getInfo(method.nameIdentifier!!))
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
