package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunConfigurationProducer
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.testFramework.run.PhpTestRunnerSettings.Scope

/**
 * Regression coverage for the gutter run line marker on the `#[Test]` attribute.
 *
 * The `#[Test]` attribute is runnable but NOT numbered (no attribute group), so
 * [PsiUtil.getAttributeOrder] returns -1 for it. The producer must still build a
 * configuration from a `#[Test]` context (plain method run), while numbered
 * attributes (DataProvider/DataSet/...) keep their `:index` suffix.
 */
class TestoRunConfigurationProducerPsiTest : BasePlatformTestCase() {

    private val producer = TestoRunConfigurationProducer()

    private fun attributeByFqn(text: String, fqn: String): PhpAttribute {
        val psiFile = myFixture.configureByText(PhpFileType.INSTANCE, text)
        return PsiTreeUtil.findChildrenOfType(psiFile, PhpAttribute::class.java)
            .first { it.fqn == fqn }
    }

    // ---- #[Test] attribute alone ----

    fun testSetupConfiguration_plainTestAttribute_producesConfiguration() {
        val attribute = attributeByFqn(
            """<?php
            namespace Testo { #[\Attribute] class Test {} }
            namespace App {
                use Testo\Test;
                class FooTest {
                    #[Test]
                    public function classDataProvider(): void {}
                }
            }
            """.trimIndent(),
            TestoClasses.TEST
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[Test] attribute must produce a run configuration", result)
        assertEquals("#[Test] runs with --type=test", "test", settings.testoType)
        assertEquals("#[Test] is not a data provider", -1, settings.dataProviderIndex)
        assertEquals("#[Test] is not a dataset", -1, settings.dataSetIndex)
        assertFalse(
            "#[Test] method name must NOT carry a numbered :index suffix, was '${settings.methodName}'",
            settings.methodName.matches(Regex(".*:\\d+$"))
        )
    }

    // ---- #[Test] together with #[DataProvider] (the reported case) ----

    fun testSetupConfiguration_testAttributeOnMethodWithDataProvider_producesConfiguration() {
        val attribute = attributeByFqn(
            """<?php
            namespace Testo { #[\Attribute] class Test {} }
            namespace Testo\Data { #[\Attribute] class DataProvider { public function __construct(${'$'}p) {} } }
            namespace App {
                use Testo\Test;
                use Testo\Data\DataProvider;
                class ClassDataProvider {}
                class FooTest {
                    #[Test]
                    #[DataProvider(new ClassDataProvider())]
                    public function classDataProvider(string ${'$'}val, mixed ${'$'}eq): void {}
                }
            }
            """.trimIndent(),
            TestoClasses.TEST
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[Test] on a DataProvider method must still produce a config", result)
        assertEquals("#[Test] runs with --type=test", "test", settings.testoType)
        assertFalse(
            "#[Test] must NOT inherit a :index suffix from sibling DataProvider, was '${settings.methodName}'",
            settings.methodName.matches(Regex(".*:\\d+$"))
        )
    }

    // ---- #[DataProvider] still works (no regression) ----

    fun testSetupConfiguration_dataProviderAttribute_producesIndexedConfiguration() {
        val attribute = attributeByFqn(
            """<?php
            namespace Testo { #[\Attribute] class Test {} }
            namespace Testo\Data { #[\Attribute] class DataProvider { public function __construct(${'$'}p) {} } }
            namespace App {
                use Testo\Test;
                use Testo\Data\DataProvider;
                class ClassDataProvider {}
                class FooTest {
                    #[Test]
                    #[DataProvider(new ClassDataProvider())]
                    public function classDataProvider(string ${'$'}val, mixed ${'$'}eq): void {}
                }
            }
            """.trimIndent(),
            TestoClasses.DATA_PROVIDER
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[DataProvider] attribute must produce a run configuration", result)
        assertEquals("#[DataProvider] runs with --type=test", "test", settings.testoType)
        assertEquals("First data attribute has index 0", 0, settings.dataProviderIndex)
        assertEquals("Not a dataset run", -1, settings.dataSetIndex)
        assertTrue(
            "#[DataProvider] method name must carry the :0 suffix, was '${settings.methodName}'",
            settings.methodName.endsWith(":0")
        )
    }

    // ---- #[TestRectorFixtures] on a Rector rule class ----

    fun testSetupConfiguration_rectorFixturesAttribute_runsTheFileWithItsOwnType() {
        val attribute = attributeByFqn(
            """<?php
            namespace App;
            #[\Testo\Bridge\Rector\Testing\TestRectorFixtures('SomeRector')]
            final class SomeRector { public function refactor(): void {} }
            """.trimIndent(),
            TestoClasses.RECTOR_TEST_FIXTURES
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[TestRectorFixtures] must produce a run configuration", result)
        assertEquals(
            "A rule's fixtures run as the synthesized rector-fixture type",
            TestoRunConfigurationProducer.RECTOR_FIXTURE_TYPE,
            settings.testoType,
        )
        assertEquals("The case is the class, so it is run through its file", Scope.File, settings.scope)
        assertTrue("No method selector for a case class", settings.methodName.isNullOrEmpty())
    }

    fun testSetupConfiguration_rectorFixturesOnTheClassItself_runsTheFileUntyped() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            namespace App;
            #[\Testo\Bridge\Rector\Testing\TestRectorFixtures('SomeRector')]
            final class SomeRector { public function refactor(): void {} }
            """.trimIndent()
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, phpClass, psiFile.virtualFile)

        assertNotNull("Running the rule class itself must produce a configuration", result)
        assertEquals("Running a class runs everything it holds — no type filter", "", settings.testoType)
        assertEquals(Scope.File, settings.scope)
    }

    fun testSetupConfiguration_plainTestClass_keepsNoTestoType() {
        val psiFile = myFixture.configureByText(
            "UserTest.php",
            """<?php class UserTest { public function testSomething(): void {} }"""
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!

        val settings = TestoRunnerSettings()
        producer.setupConfiguration(settings, phpClass, psiFile.virtualFile)

        assertEquals("A plain test class run stays untyped", "", settings.testoType)
    }

    fun testSetupConfiguration_classWithTestAttribute_keepsNoTestoType() {
        val psiFile = myFixture.configureByText(
            PhpFileType.INSTANCE,
            """<?php
            #[\Testo\Test]
            class FooTest {
                public function it_works(): void {}
                #[\Testo\Bench]
                public function bench_it(): void {}
            }
            """.trimIndent()
        )
        val phpClass = PsiTreeUtil.findChildOfType(psiFile, PhpClass::class.java)!!

        val settings = TestoRunnerSettings()
        producer.setupConfiguration(settings, phpClass, psiFile.virtualFile)

        // `--type=test` here would silently drop the class's #[Bench] methods from the run; that narrowing is only
        // applied when the run starts from the #[Test] attribute itself.
        assertEquals("Running the class itself must stay untyped", "", settings.testoType)
    }

    fun testIsConfigurationFromContext_typedAndUntypedClassRunsAreDifferentContexts() {
        val attribute = attributeByFqn(
            """<?php
            #[\Testo\Test]
            class FooTest {
                public function it_works(): void {}
                #[\Testo\Bench]
                public function bench_it(): void {}
            }
            """.trimIndent(),
            TestoClasses.TEST
        )
        val phpClass = attribute.owner as PhpClass

        val typed = TestoRunnerSettings()
        producer.setupConfiguration(typed, attribute, attribute.containingFile.virtualFile)
        val untyped = TestoRunnerSettings()
        producer.setupConfiguration(untyped, phpClass, attribute.containingFile.virtualFile)

        assertTrue(producer.isConfigurationFromContext(typed, attribute))
        assertTrue(producer.isConfigurationFromContext(untyped, phpClass))
        // Reusing across contexts is what silently loses (or force-keeps) the --type narrowing.
        assertFalse(
            "An untyped class configuration is not the attribute's context",
            producer.isConfigurationFromContext(untyped, attribute),
        )
        assertFalse(
            "A typed attribute configuration is not the class's context",
            producer.isConfigurationFromContext(typed, phpClass),
        )
    }

    fun testSetupConfiguration_testAttributeOnClass_runsTheClassWithTestType() {
        val attribute = attributeByFqn(
            """<?php
            #[\Testo\Test]
            class FooTest { public function it_works(): void {} }
            """.trimIndent(),
            TestoClasses.TEST
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[Test] on a class runs that class", result)
        assertEquals(Scope.File, settings.scope)
        assertEquals("Running from the attribute narrows to its type", "test", settings.testoType)
    }

    // ---- #[Group] — run everything in that group, nothing else ----

    fun testSetupConfiguration_groupAttribute_setsOnlyTheGroupFilter() {
        val attribute = attributeByFqn(
            """<?php
            namespace App;
            class OrderTest {
                #[\Testo\Test]
                #[\Testo\Filter\Group('db')]
                public function persistsOrder(): void {}
            }
            """.trimIndent(),
            TestoClasses.FILTER_GROUP
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[Group] must produce a run configuration", result)
        assertEquals(listOf("db"), settings.groups)
        assertEquals(
            "A group run must not be narrowed to a file or method",
            Scope.ConfigurationFile,
            settings.scope,
        )
        assertTrue("No path selector for a group run", settings.filePath.isNullOrEmpty())
        assertTrue("No method selector for a group run", settings.methodName.isNullOrEmpty())
        assertEquals("No --type for a group run", "", settings.testoType)
        assertEquals(-1, settings.dataProviderIndex)
        assertEquals(-1, settings.dataSetIndex)
    }

    fun testSetupConfiguration_groupAttributeOnClass_setsOnlyTheGroupFilter() {
        val attribute = attributeByFqn(
            """<?php
            namespace App;
            #[\Testo\Filter\Group('integration')]
            class OrderTest {
                public function testPersistsOrder(): void {}
            }
            """.trimIndent(),
            TestoClasses.FILTER_GROUP
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNotNull("#[Group] on a class must produce a run configuration", result)
        assertEquals(listOf("integration"), settings.groups)
        assertEquals(Scope.ConfigurationFile, settings.scope)
        assertTrue("A group on a class must not fall back to running that class", settings.filePath.isNullOrEmpty())
    }

    fun testSetupConfiguration_variadicGroupAttribute_keepsEveryName() {
        val attribute = attributeByFqn(
            """<?php
            namespace App;
            class OrderTest {
                #[\Testo\Test]
                #[\Testo\Filter\Group('db', 'slow')]
                public function persistsOrder(): void {}
            }
            """.trimIndent(),
            TestoClasses.FILTER_GROUP
        )

        val settings = TestoRunnerSettings()
        producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertEquals("Both names are kept, one --group flag each", listOf("db", "slow"), settings.groups)
    }

    fun testSetupConfiguration_groupAttributeWithoutArguments_producesNothing() {
        val attribute = attributeByFqn(
            """<?php
            namespace App;
            class OrderTest {
                #[\Testo\Test]
                #[\Testo\Filter\Group]
                public function persistsOrder(): void {}
            }
            """.trimIndent(),
            TestoClasses.FILTER_GROUP
        )

        val settings = TestoRunnerSettings()
        val result = producer.setupConfiguration(settings, attribute, attribute.containingFile.virtualFile)

        assertNull("A group with no names selects nothing, so there is nothing to run", result)
    }

    fun testExtractGroupNames_skipsNonLiteralArguments() {
        val attribute = attributeByFqn(
            """<?php
            namespace App;
            class OrderTest {
                #[\Testo\Filter\Group('db', SomeClass::GROUP)]
                public function persistsOrder(): void {}
            }
            """.trimIndent(),
            TestoClasses.FILTER_GROUP
        )

        assertEquals(
            "Only resolvable string literals become group names",
            listOf("db"),
            TestoRunConfigurationProducer.extractGroupNames(attribute),
        )
    }

    // ---- getAttributeOrder contract that the producer relies on ----

    fun testGetAttributeOrder_testAttributeIsUnindexed() {
        val attribute = attributeByFqn(
            """<?php
            namespace Testo { #[\Attribute] class Test {} }
            namespace App {
                use Testo\Test;
                class FooTest {
                    #[Test]
                    public function bar(): void {}
                }
            }
            """.trimIndent(),
            TestoClasses.TEST
        )
        val owner = attribute.owner as com.jetbrains.php.lang.psi.elements.PhpAttributesOwner
        assertEquals("#[Test] must be unindexed (-1)", -1, PsiUtil.getAttributeOrder(attribute, owner))
    }
}
