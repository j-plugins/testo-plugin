package com.github.xepozz.testo

import com.intellij.openapi.application.WriteAction
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MixinPsiTest : BasePlatformTestCase() {

    // ---- isTestoClass ----

    fun testIsTestoClass_classWithTestSuffix() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertTrue("Class ending with 'Test' should be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoClass_classWithTestBaseSuffix() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class AbstractTestBase { public function testBase(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertTrue("Class ending with 'TestBase' should be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoClass_regularClass() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class UserService { public function getUser(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertFalse("Regular class should not be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoClass_classWithTestMethodButNoSuffix() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class MyFeature { public function testSomething(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertTrue("Class with test methods should be a Testo class", phpClass.isTestoClass())
    }

    // ---- isTestoMethod ----

    fun testIsTestoMethod_publicMethodStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function testSomething(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertTrue("Public method starting with 'test' should be a Testo method", method.isTestoMethod())
    }

    fun testIsTestoMethod_privateMethodStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { private function testSomething(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Private method starting with 'test' should not be a Testo method", method.isTestoMethod())
    }

    fun testIsTestoMethod_publicMethodNotStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function helper(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Public method not starting with 'test' should not be a Testo method", method.isTestoMethod())
    }

    fun testIsTestoMethod_protectedMethodStartingWithTest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { protected function testProtected(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Protected method starting with 'test' should not be a Testo method", method.isTestoMethod())
    }

    // ---- isTestoDataProviderLike ----

    fun testIsTestoDataProviderLike_publicStaticMethod() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public static function provideData(): iterable { yield [1]; } }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertTrue("Public static method should be data provider like", method.isTestoDataProviderLike())
    }

    fun testIsTestoDataProviderLike_publicNonStaticMethod() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function provideData(): iterable { yield [1]; } }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Public non-static method should not be data provider like", method.isTestoDataProviderLike())
    }

    fun testIsTestoDataProviderLike_privateStaticMethod() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { private static function provideData(): iterable { yield [1]; } }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Private static method should not be data provider like", method.isTestoDataProviderLike())
    }

    fun testIsTestoDataProviderLike_function() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php function provideData(): iterable { yield [1]; }"""
        )
        val function = psiFile.phpFunctions()
            .first { !it.isMethod }.psi
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

    fun testIsTestoFile_invalidVirtualFile_returnsFalse() {
        val psiFile = myFixture.configureByText(
            "DeletedTest.php",
            """<?php class DeletedTest { public function testSomething(): void {} }"""
        )

        assertTrue("Precondition: file should initially be detected as a Testo file", psiFile.isTestoFile())

        val vFile = psiFile.virtualFile!!
        WriteAction.runAndWait<Throwable> { vFile.delete(this) }

        assertFalse("VirtualFile should be invalid after deletion", vFile.isValid)
        assertFalse(
            "isTestoFile must return false when the underlying VirtualFile is invalid",
            psiFile.isTestoFile()
        )
    }

    // ---- isTestoExecutable ----

    fun testIsTestoExecutable_testMethod() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function testSomething(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertTrue("Test method should be executable", method.isTestoExecutable())
    }

    fun testIsTestoExecutable_nonTestMethod() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function helper(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Non-test method should not be executable", method.isTestoExecutable())
    }

    // ---- isTestoClassFile / isTestoFunctionFile ----

    fun testIsTestoClassFile() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class SomeTest { public function testA(): void {} }"""
        )
        assertTrue("File with test class should be a Testo class file", psiFile.isTestoClassFile())
    }

    fun testIsTestoClassFile_noTestClass() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Helper { public function doStuff(): void {} }"""
        )
        assertFalse("File without test class should not be a Testo class file", psiFile.isTestoClassFile())
    }

    fun testIsTestoFile_namespacedBenchClass() {
        val psiFile = myFixture.configureByText(
            "Benchmarks.php",
            """<?php namespace App\Perf { class Sorting { #[\Testo\Bench] public function sort(): void {} } }"""
        )
        assertTrue("A namespaced class holding a bench makes a Testo file", psiFile.isTestoFile())
    }

    fun testIsTestoFile_namespacedTestFunction() {
        val psiFile = myFixture.configureByText(
            "checks.php",
            """<?php namespace App; #[\Testo\Test] function checksSomething(): void {}"""
        )
        assertTrue("A namespaced test function makes a Testo file", psiFile.isTestoFile())
    }

    fun testIsTestoFile_configFile() {
        val psiFile = myFixture.configureByText(
            "testo.php",
            """<?php use Testo\Application\Config\ApplicationConfig; return new ApplicationConfig();"""
        )
        assertTrue("A file building an ApplicationConfig is a Testo file", psiFile.isTestoFile())
    }

    fun testIsTestoFile_anonymousTestClassIsIgnored() {
        val psiFile = myFixture.configureByText(
            "factory.php",
            """<?php return new class { public function testA(): void {} };"""
        )
        assertFalse("An anonymous class is no test case", psiFile.isTestoFile())
    }

    // ---- Multiple methods in one class ----

    fun testMultipleMethods_mixedTestAndNonTest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            class UserTest {
                public function testCreate(): void {}
                public function testDelete(): void {}
                public function setUp(): void {}
                private function helper(): void {}
            }"""
        )
        val methods = psiFile.phpMethods()
        val testMethods = methods.filter { it.psi.isTestoMethod() }
        val nonTestMethods = methods.filter { !it.psi.isTestoMethod() }

        assertEquals("Should find 2 test methods", 2, testMethods.size)
        assertEquals("Should find 2 non-test methods", 2, nonTestMethods.size)
        assertTrue(testMethods.any { it.name == "testCreate" })
        assertTrue(testMethods.any { it.name == "testDelete" })
    }

    // ---- Edge cases ----

    fun testIsTestoClass_emptyClass() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class EmptyTest {}"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertTrue("Empty class ending with 'Test' should still be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoMethod_methodNamedExactlyTest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function test(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertTrue("Method named exactly 'test' should be a Testo method", method.isTestoMethod())
    }

    // ---- Class-level #[Testo\Test] attribute ----

    fun testIsTestoClass_classLevelTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            namespace App;
            #[\Testo\Test]
            class UserService { public function it_works(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertTrue("Class with #[Testo\\Test] attribute should be a Testo class", phpClass.isTestoClass())
    }

    fun testIsTestoMethod_publicMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Test]
            class Foo { public function it_works(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertTrue("Public method in class marked with #[Testo\\Test] should be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_privateMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Test]
            class Foo { private function helper(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Private method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_staticMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Test]
            class Foo { public static function provide(): iterable { yield [1]; } }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Public static method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_magicMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Test]
            class Foo { public function __construct() {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Magic method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_abstractMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Test]
            abstract class Foo { abstract public function it_works(): void; }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Abstract method in #[Testo\\Test] class should not be runnable", method.isTestoMethod())
    }

    fun testIsTestoMethod_publicMethodInRegularClass() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class Foo { public function it_works(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Public method in non-Testo class should not be runnable", method.isTestoMethod())
    }

    // ---- Class-level case attribute #[TestRectorFixtures] ----

    fun testIsTestoClass_classWithRectorFixturesAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            namespace App;
            #[\Testo\Bridge\Rector\Testing\TestRectorFixtures('SomeRector')]
            final class SomeRector { public function refactor(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertTrue("A rule with #[TestRectorFixtures] is a Testo case class", phpClass.isTestoClass())
        assertTrue(phpClass.isTestoCaseClass())
    }

    fun testIsTestoMethod_publicMethodOfRectorFixturesClassIsNotATest() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Bridge\Rector\Testing\TestRectorFixtures('SomeRector')]
            final class SomeRector {
                public function getRuleDefinition(): void {}
                public function refactor(): void {}
            }"""
        )
        val methods = psiFile.phpMethods()
        assertEquals(2, methods.size)
        for (method in methods) {
            assertFalse(
                "The rule's own methods are not tests — the fixtures are (method '${method.name}')",
                method.psi.isTestoMethod()
            )
        }
    }

    fun testIsTestoCaseClass_plainTestClassIsNotACaseClass() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        assertFalse("Only a class-level case attribute makes a case class", phpClass.isTestoCaseClass())
    }

    fun testIsTestoFile_rectorRuleWithFixturesAttribute() {
        val psiFile = myFixture.configureByText(
            "SomeRector.php",
            """<?php
            #[\Testo\Bridge\Rector\Testing\TestRectorFixtures('SomeRector')]
            final class SomeRector { public function refactor(): void {} }"""
        )
        assertTrue("A rule declaring fixtures is a Testo file", psiFile.isTestoFile())
    }

    fun testIsTestoMethod_benchMethodInClassWithTestAttribute() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php
            #[\Testo\Test]
            class Foo {
                #[\Testo\Bench]
                public function bench_it(): void {}
            }"""
        )
        val method = psiFile.phpMethods().first().psi
        assertFalse("Bench method should not be reported as a Testo test method", method.isTestoMethod())
        assertTrue("Bench method should still be detected as a Testo bench", method.isTestoBench())
    }

    // ---- resolveHierarchy gate: the indexer path must not query the global PHP index ----

    fun testResolveHierarchy_abstractBaseKnownOnlyByTestSubclass() {
        myFixture.addFileToProject("FooTest.php", """<?php class FooTest extends AbstractCase {}""")
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php abstract class AbstractCase { public function provider(): array { return []; } }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        val method = psiFile.phpMethods().first().psi

        assertTrue("A test subclass makes the abstract base a Testo class when the hierarchy is resolved", phpClass.isTestoClass())
        assertTrue(method.isTestoMethod())

        assertFalse("The indexer path must not resolve the hierarchy (no global-index query)", phpClass.isTestoClass(resolveHierarchy = false))
        assertFalse(method.isTestoMethod(resolveHierarchy = false))
    }

    // ---- #[Test] on a base class marks every inheritor a case ----

    fun testIsTestoClass_inheritorOfAttributedAbstractBase() {
        myFixture.addFileToProject(
            "BasePersistenceCase.php",
            """<?php #[\Testo\Test] abstract class BasePersistenceCase { public function persists(): void {} }"""
        )
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php final class PersistenceCase extends BasePersistenceCase { public function alsoPersists(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        val method = psiFile.phpMethods().first().psi

        assertTrue("#[Test] on the base makes the inheritor a case", phpClass.isTestoClass())
        assertTrue("A public method of such an inheritor is a test", method.isTestoMethod())

        assertFalse("The indexer path must not resolve the hierarchy", phpClass.isTestoClass(resolveHierarchy = false))
        assertFalse(method.isTestoMethod(resolveHierarchy = false))
    }

    fun testIsTestoClass_inheritorWithNoOwnMethods() {
        myFixture.addFileToProject(
            "BaseHttpCase.php",
            """<?php #[\Testo\Test] abstract class BaseHttpCase { public function sends(): void {} }"""
        )
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php final class HttpCase extends BaseHttpCase {}"""
        )
        val phpClass = psiFile.phpClasses().first().psi

        assertTrue("An inheritor running only inherited tests is still a case", phpClass.isTestoClass())
    }

    fun testIsTestoClass_attributeTwoLevelsUpTheHierarchy() {
        myFixture.addFileToProject(
            "GrandBaseCase.php",
            """<?php #[\Testo\Test] abstract class GrandBaseCase { public function roots(): void {} }"""
        )
        myFixture.addFileToProject(
            "MidBaseCase.php",
            """<?php abstract class MidBaseCase extends GrandBaseCase {}"""
        )
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php final class LeafCase extends MidBaseCase { public function grows(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi
        val method = psiFile.phpMethods().first().psi

        assertTrue("The ancestor walk is transitive, not direct-parent-only", phpClass.isTestoClass())
        assertTrue(method.isTestoMethod())
    }

    fun testIsTestoClass_cyclicHierarchyTerminates() {
        // Invalid PHP the PSI happily holds mid-typing; the walk must terminate, not hang the read action.
        myFixture.addFileToProject("CycleB.php", """<?php abstract class CycleB extends CycleA {}""")
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php final class CycleA extends CycleB { public function spins(): void {} }"""
        )
        val phpClass = psiFile.phpClasses().first().psi

        assertFalse("No attribute anywhere in the cycle — not a case", phpClass.isTestoClass())
    }

    fun testIsTestoMethod_methodDeclaredInAttributedAbstractBase() {
        val psiFile = myFixture.configureByText(
            PHP_TEST_FILE,
            """<?php #[\Testo\Test] abstract class BaseQueueCase { public function consumes(): void {} }"""
        )
        val method = psiFile.phpMethods().first().psi

        assertTrue("The declaration in the base carries the gutter; the run goes through inheritors", method.isTestoMethod())
    }
}
