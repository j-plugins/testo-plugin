package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunConfigurationProducer
import com.github.xepozz.testo.tests.run.TestoRunnerSettings
import com.github.xepozz.testo.util.PsiUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.PhpAttribute

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
