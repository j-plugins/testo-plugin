package com.github.xepozz.testo

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MixinPsiTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    // ---- isTestoClass ----

    fun testIsTestoClass_classWithTestSuffix() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        assertTrue("Class ending with 'Test' should be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoClass_classWithTestBaseSuffix() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class AbstractTestBase { public function testBase(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        assertTrue("Class ending with 'TestBase' should be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoClass_regularClass() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class UserService { public function getUser(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        assertFalse("Regular class should not be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoClass_classWithTestMethodButNoSuffix() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class MyFeature { public function testSomething(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        assertTrue("Class with test methods should be a Testo class", phpClass.isTestoClass())
    }

    // ---- isTestoMethod ----

    fun testIsTestoMethod_publicMethodStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function testSomething(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertTrue("Public method starting with 'test' should be a Testo method", method.isTestoMethod())
    }

    fun testIsTestoMethod_privateMethodStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { private function testSomething(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Private method starting with 'test' should not be a Testo method", method.isTestoMethod())
    }

    fun testIsTestoMethod_publicMethodNotStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function helper(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Public method not starting with 'test' should not be a Testo method", method.isTestoMethod())
    }

    fun testIsTestoMethod_protectedMethodStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { protected function testProtected(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Protected method starting with 'test' should not be a Testo method", method.isTestoMethod())
    }

    // ---- isTestoDataProviderLike ----

    fun testIsTestoDataProviderLike_publicStaticMethod() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public static function provideData(): iterable { yield [1]; } }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertTrue("Public static method should be data provider like", method.isTestoDataProviderLike())
    }

    fun testIsTestoDataProviderLike_publicNonStaticMethod() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function provideData(): iterable { yield [1]; } }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Public non-static method should not be data provider like", method.isTestoDataProviderLike())
    }

    fun testIsTestoDataProviderLike_privateStaticMethod() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { private static function provideData(): iterable { yield [1]; } }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Private static method should not be data provider like", method.isTestoDataProviderLike())
    }

    fun testIsTestoDataProviderLike_function() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php function provideData(): iterable { yield [1]; }"""
        )
        val function = PsiTreeUtil.findChildrenOfType(psiFile, Function::class.java)
            .first { it !is Method }
        assertTrue("Standalone function should be data provider like", function.isTestoDataProviderLike())
    }

    // ---- isTestoFile ----

    fun testIsTestoFile_fileNameEndingWithTest() {
        val psiFile = myFixture.configureByText(
            "UserTest.php",
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        assertTrue("File named *Test.php should be a Testo file", psiFile.isTestoFile())
    }

    fun testIsTestoFile_regularFile() {
        val psiFile = myFixture.configureByText(
            "UserService.php",
            """<?php class UserService { public function getUser(): void {} }"""
        )
        assertFalse("Regular PHP file should not be a Testo file", psiFile.isTestoFile())
    }

    fun testIsTestoFile_fileWithTestClass() {
        val psiFile = myFixture.configureByText(
            "Features.php",
            """<?php class FeatureTest { public function testFeature(): void {} }"""
        )
        assertTrue("File containing test class should be a Testo file", psiFile.isTestoFile())
    }

    // ---- isTestoExecutable ----

    fun testIsTestoExecutable_testMethod() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function testSomething(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertTrue("Test method should be executable", method.isTestoExecutable())
    }

    fun testIsTestoExecutable_nonTestMethod() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function helper(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Non-test method should not be executable", method.isTestoExecutable())
    }

    // ---- isTestoClassFile / isTestoFunctionFile ----

    fun testIsTestoClassFile() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class SomeTest { public function testA(): void {} }"""
        ) as PhpFile
        assertTrue("File with test class should be a Testo class file", psiFile.isTestoClassFile())
    }

    fun testIsTestoClassFile_noTestClass() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Helper { public function doStuff(): void {} }"""
        ) as PhpFile
        assertFalse("File without test class should not be a Testo class file", psiFile.isTestoClassFile())
    }

    // ---- Multiple methods in one class ----

    fun testMultipleMethods_mixedTestAndNonTest() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            class UserTest {
                public function testCreate(): void {}
                public function testDelete(): void {}
                public function setUp(): void {}
                private function helper(): void {}
            }"""
        )
        val methods = PsiTreeUtil.findChildrenOfType(psiFile, Method::class.java)
        val testMethods = methods.filter { it.isTestoMethod() }
        val nonTestMethods = methods.filter { !it.isTestoMethod() }

        assertEquals("Should find 2 test methods", 2, testMethods.size)
        assertEquals("Should find 2 non-test methods", 2, nonTestMethods.size)
        assertTrue(testMethods.any { it.name == "testCreate" })
        assertTrue(testMethods.any { it.name == "testDelete" })
    }

    // ---- Edge cases ----

    fun testIsTestoClass_emptyClass() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class EmptyTest {}"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        assertTrue("Empty class ending with 'Test' should still be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoMethod_methodNamedExactlyTest() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function test(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertTrue("Method named exactly 'test' should be a Testo method", method.isTestoMethod())
    }

    // ---- Class-level #[Testo\Test] attribute ----

    fun testIsTestoClass_classLevelTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            namespace App;
            #[\Testo\Test]
            class UserService { public function it_works(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!
        assertTrue("Class with #[Testo\\Test] attribute should be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoMethod_publicMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            class Foo { public function it_works(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertTrue("Public method in class marked with #[Testo\\Test] should be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_privateMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            class Foo { private function helper(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Private method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_staticMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            class Foo { public static function provide(): iterable { yield [1]; } }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Public static method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_magicMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            class Foo { public function __construct() {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Magic method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_abstractMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            abstract class Foo { abstract public function it_works(): void; }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Abstract method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_publicMethodInRegularClass() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php class Foo { public function it_works(): void {} }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Public method in non-Testo class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_benchMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            class Foo {
                #[\Testo\Bench]
                public function bench_it(): void {}
            }"""
        )
        val method = PsiTreeUtil.findChildOfType(psiFile, Method::class.java)!!
        assertFalse("Bench method should not be reported as a Testo test method", method.isTestoMethod())
        assertTrue("Bench method should still be detected as a Testo bench", method.isTestoBench())
    }
}
