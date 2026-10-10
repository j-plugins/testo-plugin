package com.github.xepozz.testo.php

import com.github.xepozz.testo.launch.TestoConfiguration
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What the plugin relies on from a PHP implementation, asked through [TestoPhp] only — the same questions any
 * implementation has to answer the same way.
 */
class TestoPhpContractTest : BasePlatformTestCase() {
    private val php get() = TestoPhp.getInstance()

    private fun file(name: String, text: String): PsiFile = myFixture.configureByText(name, text.trimIndent())

    /** The PSI declaration whose name starts at [anchor] in [file]. */
    private fun declaration(file: PsiFile, anchor: String): PsiElement =
        checkNotNull(file.findElementAt(file.text.indexOf(anchor))?.parent) { "Nothing at '$anchor'" }

    fun testMethodOfAClassInANamespace() {
        val file = file("FooTest.php", "<?php namespace App; class FooTest { public static function data() {} }")
        val method = php.view(declaration(file, "data")) as PhpFunctionView

        assertEquals("data", method.name)
        assertTrue(method.isMethod)
        assertEquals("\\App\\FooTest", method.containingClass?.fqn)
        assertTrue(method.isPublic)
        assertTrue(method.isStatic)
        assertFalse(method.isAbstract)
        assertEquals(PhpLeafKind.IDENTIFIER, php.leafKind(method.nameIdentifier!!))
    }

    fun testFreeFunction() {
        val file = file("functions.php", "<?php namespace Ns; function medianOf() {}")
        val function = php.view(declaration(file, "medianOf")) as PhpFunctionView

        assertFalse(function.isMethod)
        assertNull(function.containingClass)
        assertEquals("\\Ns\\medianOf", function.fqn)
        assertEquals(listOf("medianOf"), php.topLevelFunctions(file).map { it.name })
    }

    fun testClassesOfEveryNamespaceBlock() {
        val file = file("blocks.php", "<?php namespace A { class X {} } namespace B { class Y {} }")

        assertEquals(setOf("\\A\\X", "\\B\\Y"), php.allClasses(file).map { it.fqn }.toSet())
        assertTrue(php.isPhpFile(file))
    }

    fun testAttributeNameResolvedThroughUse() {
        val file = file(
            "CTest.php",
            "<?php namespace App; use Testo\\Test; class CTest { #[Test] public function t() {} }",
        )
        val method = php.view(declaration(file, "t()")) as PhpFunctionView

        assertEquals(listOf("\\Testo\\Test"), method.attributes.map { it.fqn })
        assertEquals(1, method.attributes("\\Testo\\Test").size)
        assertEquals(method.psi, method.attributes.single().owner)
    }

    fun testProviderArguments() {
        val file = file(
            "ProviderTest.php",
            """
            <?php
            namespace App;
            use Testo\Data\DataProvider;
            class ProviderTest {
                #[DataProvider('cases')] public function byPosition() {}
                #[DataProvider(provider: 'cases')] public function byName() {}
                #[DataProvider([self::class, 'cases'])] public function asPair() {}
                #[DataProvider(new \App\Cases())] public function asObject() {}
            }
            """,
        )
        fun argumentOf(method: String) =
            (php.view(declaration(file, method)) as PhpFunctionView).attributes.single().argument("provider", 0)

        assertEquals(PhpArgumentText("'cases'", true), argumentOf("byPosition"))
        assertEquals(PhpArgumentText("'cases'", true), argumentOf("byName"))
        assertEquals(PhpArgumentText("[self::class, 'cases']", false), argumentOf("asPair"))
        // Either no answer or its text; what matters is that it is no string literal, so no provider is read from it.
        assertFalse(argumentOf("asObject")?.isStringLiteral ?: false)
    }

    fun testSubclassesAndSuperclasses() {
        val file = file(
            "hierarchy.php",
            """
            <?php
            abstract class BaseTest {}
            final class UserTest extends BaseTest {}
            #[\Testo\Test] class BaseCase {}
            class OrderCase extends BaseCase {}
            """,
        )
        assertEquals(listOf("\\UserTest"), php.allSubclasses(project, "\\BaseTest").map { it.fqn })

        val orderCase = php.view(declaration(file, "OrderCase")) as PhpClassView
        assertTrue(php.anySuperClass(orderCase) { superClass -> superClass.attributes("\\Testo\\Test").isNotEmpty() })
        val baseCase = php.view(declaration(file, "BaseCase")) as PhpClassView
        assertFalse("The class itself is not its own superclass", php.anySuperClass(baseCase) { it.fqn == "\\BaseCase" })
        assertEquals(listOf("\\BaseCase"), php.classesByFqn(project, "\\BaseCase").map { it.fqn })
    }

    fun testHierarchyAnswersFollowAnEdit() {
        val file = file(
            "edited.php",
            """
            <?php
            abstract class EditedBase {}
            class OtherBase {}
            class Plain {}
            """,
        )
        assertEquals(emptyList<String>(), php.allSubclasses(project, "\\EditedBase").map { it.fqn })
        assertFalse(php.anySuperClass(php.view(declaration(file, "Plain")) as PhpClassView) { it.fqn == "\\OtherBase" })

        // A new file alone: the heir is found without an edit of the file already asked about.
        myFixture.addFileToProject("heir.php", "<?php\nclass LateHeir extends EditedBase {}\n")
        assertEquals(listOf("\\LateHeir"), php.allSubclasses(project, "\\EditedBase").map { it.fqn })

        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val at = document.text.indexOf("class Plain {}")
            document.replaceString(at, at + "class Plain {}".length, "class Plain extends OtherBase {}")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        assertTrue(php.anySuperClass(php.view(declaration(file, "Plain")) as PhpClassView) { it.fqn == "\\OtherBase" })
    }

    fun testSuiteConfigurationObject() {
        val file = file(
            "testo.php",
            "<?php use Testo\\Application\\Config\\SuiteConfig; return [new SuiteConfig('Unit'), new SuiteConfig(\$name)];",
        )
        val suites = php.classReferencesIn(file).filter { it.newExpression != null }

        assertEquals(List(2) { "\\Testo\\Application\\Config\\SuiteConfig" }, suites.map { it.fqn })
        assertEquals(listOf("Unit", null), suites.map { it.newExpression?.firstStringArgument })
    }

    fun testExitStatements() {
        val file = file(
            "provider.php",
            "<?php function cases(): iterable { yield [1]; yield [2]; return; }",
        )
        val yieldKeyword = file.findElementAt(file.text.indexOf("yield [2]"))!!

        assertEquals(PhpLeafKind.YIELD, php.leafKind(yieldKeyword))
        assertEquals(PhpLeafKind.RETURN, php.leafKind(file.findElementAt(file.text.indexOf("return"))!!))
        assertTrue(php.isExitStatement(yieldKeyword.parent))
        // Counted among the keyword's own siblings, not the function's statements — see the gutter baseline.
        assertEquals(0, php.precedingExitKeywords(yieldKeyword))
    }

    fun testCallDeclaringTheAssertionException() {
        val file = file(
            "AssertTest.php",
            """
            <?php
            class Assert {
                /** @throws \Testo\Assert\State\Assertion\AssertionException */
                public static function same() {}
                public static function quiet() {}
            }
            Assert::same();
            Assert::quiet();
            """,
        )
        fun call(name: String) = file.findElementAt(file.text.lastIndexOf("::$name") + 2)!!.parent
        val exception = "\\Testo\\Assert\\State\\Assertion\\AssertionException"

        assertTrue(php.callThrows(call("same"), exception))
        assertFalse(php.callThrows(call("quiet"), exception))
    }

    fun testClosureAndArrowFunctionAreFunctions() {
        val file = file(
            "closures.php",
            "<?php class Cases { public static function all() { \$a = function () { return 1; }; \$b = fn() => 2; } }",
        )
        fun functionAround(anchor: String) = enclosingFunction(file.findElementAt(file.text.indexOf(anchor))!!)

        for (anchor in listOf("return 1", "2; }")) {
            val function = functionAround(anchor)
            assertFalse("The function around '$anchor' is its own, not the method", function.isMethod)
            assertNull(function.containingClass)
        }
        assertTrue(functionAround("function all").isMethod)
    }

    fun testYieldInsideAClosureBelongsToTheClosure() {
        val file = file(
            "ProviderTest.php",
            "<?php class ProviderTest { public static function cases() { yield [1]; \$f = function () { yield [2]; }; } }",
        )
        val inner = file.findElementAt(file.text.indexOf("yield [2]"))!!

        assertEquals(PhpLeafKind.YIELD, php.leafKind(inner))
        assertTrue(php.isYield(inner.parent))
        assertFalse("A yield in a closure is no data set of the method", enclosingFunction(inner).isMethod)
    }

    fun testYieldFrom() {
        val file = file("provider.php", "<?php function cases(): iterable { yield from [[1]]; }")
        val keyword = file.findElementAt(file.text.indexOf("yield from"))!!

        assertEquals(PhpLeafKind.YIELD, php.leafKind(keyword))
        assertTrue(php.isYield(keyword.parent))
        assertTrue(php.isExitStatement(keyword.parent))
    }

    fun testAnonymousClassIsAClass() {
        val file = file("anonymous.php", "<?php \$case = new class { public function testRuns() {} };")
        val method = php.view(declaration(file, "testRuns")) as PhpFunctionView

        assertTrue(method.isMethod)
        val cls = checkNotNull(method.containingClass) { "An anonymous class is a class" }
        assertEquals(cls, php.view(cls.psi))
    }

    fun testALeafInTheClassBodySeesItsClass() {
        val file = file("BodyTest.php", "<?php namespace App; class BodyTest {\n\n    public function a() {}\n}")
        val offset = file.text.indexOf("\n\n") + 1
        val leaf = file.findElementAt(offset)!!
        val cls = php.view(leaf.parent) as PhpClassView

        assertEquals("\\App\\BodyTest", cls.fqn)
        assertTrue(cls.bodyStartOffset!! <= leaf.textOffset)
    }

    fun testInterfacesAreAbstractAndEnumsFinal() {
        val file = file("kinds.php", "<?php interface Case_ {} enum Suit {} abstract class Base {} class Plain {}")
        fun cls(name: String) = php.view(declaration(file, name)) as PhpClassView

        assertTrue(cls("Case_").isAbstract)
        assertTrue(cls("Suit").isFinal)
        assertTrue(cls("Base").isAbstract)
        assertFalse(cls("Plain").isAbstract)
        assertFalse(cls("Plain").isFinal)
    }

    fun testAnInterfaceIsNoSuperclass() {
        val file = file("marked.php", "<?php #[\\Testo\\Test] interface Marked {} class Impl implements Marked {}")
        val impl = php.view(declaration(file, "Impl")) as PhpClassView

        assertFalse(php.anySuperClass(impl) { it.attributes("\\Testo\\Test").isNotEmpty() })
    }

    fun testATraitMethodIsFoundThroughTheClass() {
        val file = file(
            "TraitTest.php",
            "<?php trait Checks { public function fromTrait() {} } class TraitTest { use Checks; public function own() {} }",
        )
        val cls = php.view(declaration(file, "TraitTest")) as PhpClassView

        assertEquals("fromTrait", cls.findMethod("fromTrait")?.name)
        assertNull("A trait method is not the class's own", cls.findOwnMethod("fromTrait"))
        assertEquals(listOf("own"), cls.ownMethods.map { it.name })
    }

    fun testStringArgumentContentsAreBetweenTheQuotes() {
        val file = file(
            "GroupTest.php",
            "<?php class GroupTest { #[\\Testo\\Filter\\Group('db', \"slow\", self::NAME)] public function t() {} }",
        )
        val attribute = (php.view(declaration(file, "t()")) as PhpFunctionView).attributes.single()

        assertEquals(listOf("db", "slow", null), attribute.parameters.map { attribute.stringContents(it) })
    }

    fun testOnlyTheLastSegmentOfAWrittenNameIsAnIdentifierLeaf() {
        val file = file(
            "testo.php",
            "<?php return new \\Testo\\Application\\Config\\ApplicationConfig();",
        )
        val leaves = generateSequence(PsiTreeUtil.firstChild(file)) { PsiTreeUtil.nextLeaf(it) }
            .filter { php.leafKind(it) == PhpLeafKind.IDENTIFIER && php.view(it.parent) is PhpClassReferenceView }
            .map { it.text }
            .toList()

        assertEquals(listOf("ApplicationConfig"), leaves)
    }

    /** The nearest function around [element], as the core looks for one: through [TestoPhp.view]. */
    private fun enclosingFunction(element: PsiElement): PhpFunctionView =
        generateSequence(element.parent) { it.parent }.firstNotNullOf { php.view(it) as? PhpFunctionView }

    fun testLocalInterpreterPathsAreUnchanged() {
        val paths = php.projectPaths(project)

        assertEquals("/work/app/tests/FooTest.php", FileUtil.toSystemIndependentName(paths.toEnvironment("/work/app/tests/FooTest.php")))
    }

    fun testTestoIsNotConfiguredWithoutSettings() {
        assertFalse(php.isTestoConfigured(project))
    }

    fun testConfigurationsAreTestoConfigurationsOfTheSavedType() {
        val factory = php.configurationFactory()

        assertInstanceOf(factory.createTemplateConfiguration(project), TestoConfiguration::class.java)
        assertEquals("The type id saved configurations carry", "TestoRunConfiguration", factory.type.id)
    }
}
