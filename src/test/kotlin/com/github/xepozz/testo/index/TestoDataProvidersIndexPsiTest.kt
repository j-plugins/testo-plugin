package com.github.xepozz.testo.index

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FileContentImpl
import com.intellij.util.indexing.IndexingDataKeys

class TestoDataProvidersIndexPsiTest : BasePlatformTestCase() {
    private val indexer = TestoDataProvidersIndex().indexer

    fun testPhysicalFileWithoutProviderDoesNotNeedPsi() {
        val file = lightFile(PLAIN_PHP)
        // No project is attached. An access to PSI here would fail.
        val content = FileContentImpl.createByContent(file, PLAIN_PHP.toByteArray())
        assertTrue(indexer.map(content).isEmpty())
    }

    fun testPhysicalProvidersKeepImplicitAndExplicitNames() {
        val forms = listOf(
            "" to "\\Testo\\Data\\DataProvider",
            "use Testo\\Data\\DataProvider as Cases;" to "Cases",
            "use Testo\\Data\\{DataProvider as Cases};" to "Cases",
            "use Testo\\Data as Data;" to "Data\\DataProvider",
        )
        for ((imports, attribute) in forms) {
            val text = provider(imports, attribute)
            val actual = indexer.map(physical(text))
            assertEquals(setOf("cases"), actual.keys)
            assertEquals(
                setOf(TestoDataProvidersIndex.DataProviderUsage("\\TestoIndexFixture\\Provider", "runTest", "\\TestoIndexFixture\\Provider")),
                actual["cases"],
            )
        }

        val mixedCase = provider("use Testo\\Data\\dAtApRoViDeR as Cases;", "Cases")
        val expected = indexer.map(transient(mixedCase))
        val content = physical(mixedCase)
        assertTrue(mayContainDataProvider(content))
        assertEquals(expected, indexer.map(content))
    }

    fun testCommentOrUnrelatedAttributeDoesNotCreateAnEntry() {
        val text = """
            <?php
            namespace TestoIndexFixture;
            #[\Testo\Test]
            class Example {
                /** DataProvider is mentioned in the comment. */
                #[\Other\DataProvider('wrong')]
                public function runTest() {}
            }
        """.trimIndent()
        assertTrue(indexer.map(physical(text)).isEmpty())
    }

    fun testTransientDocumentKeepsExistingPsiResult() {
        val psi = myFixture.configureByText("Provider.php", provider("", "\\Testo\\Data\\DataProvider"))
        val documents = PsiDocumentManager.getInstance(project)
        val document = checkNotNull(documents.getDocument(psi))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(PLAIN_PHP)
        }
        assertTrue(documents.isUncommited(document))
        val input = FileContentImpl.createByText(psi.virtualFile, document.immutableCharSequence, project)
        assertEquals(setOf("cases"), indexer.map(input).keys)
    }

    fun testPhysicalExplicitPsiAndUnknownContentKeepExistingPsiResult() {
        val psi = myFixture.configureByText("Provider.php", provider("", "\\Testo\\Data\\DataProvider"))
        val explicit = physical(PLAIN_PHP)
        explicit.putUserData(IndexingDataKeys.PSI_FILE, psi)
        assertEquals(setOf("cases"), indexer.map(explicit).keys)

        val delegated = object : FileContent by physical(PLAIN_PHP) {
            override fun getPsiFile(): PsiFile = psi
        }
        assertEquals(setOf("cases"), indexer.map(delegated).keys)
    }

    fun testPhysicalInputWithSuppliedPsiUsesItsCommittedText() {
        val psi = myFixture.configureByText("Provider.php", provider("", "\\Testo\\Data\\DataProvider"))
        val documents = PsiDocumentManager.getInstance(project)
        val document = checkNotNull(documents.getDocument(psi))
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(PLAIN_PHP)
        }
        assertTrue(documents.isUncommited(document))

        val input = physical(PLAIN_PHP)
        input.putUserData(IndexingDataKeys.PSI_FILE, psi)
        assertEquals(setOf("cases"), indexer.map(input).keys)
    }

    private fun provider(imports: String, attribute: String): String = """
        <?php
        namespace TestoIndexFixture;
        $imports
        #[\Testo\Test]
        class Provider {
            #[$attribute('cases')]
            public function runTest() {}
            public static function cases() {}
        }
    """.trimIndent()

    private fun lightFile(text: String): LightVirtualFile =
        LightVirtualFile("Provider.php", FileTypeManager.getInstance().getFileTypeByExtension("php"), text)

    private fun physical(text: String): FileContent =
        FileContentImpl.createByContent(lightFile(text), { text.toByteArray() }, project)

    private fun transient(text: String): FileContent = FileContentImpl.createByText(lightFile(text), text, project)

    companion object {
        private const val PLAIN_PHP = "<?php class Ordinary {}"
    }
}
