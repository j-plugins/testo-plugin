package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.actions.TestoGenerateTestMethodAction
import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import ru.openide.openphp.run.testing.PhpNewTestAction

/** Generate → Test Method in a Testo class, and New → Testo Test in a directory of the project's PSR-4 map. */
class TestoGenerateAndNewTestTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    fun testATestMethodIsInsertedIntoTheClassBodyAndFormatted() {
        myFixture.configureByText(
            "CalcTest.php",
            "<?php\nnamespace App;\n\nuse Testo\\Test;\n\nfinal class CalcTest\n{\n    #[Test]\n    public function adds(): void {}<caret>\n}\n",
        )
        val action = TestoGenerateTestMethodAction()
        assertTrue(action.isValidForFile(project, myFixture.editor, myFixture.file))

        WriteCommandAction.runWriteCommandAction(project) { action.invoke(project, myFixture.editor, myFixture.file) }
        TemplateManagerImpl.getTemplateState(myFixture.editor)?.gotoEnd(false)

        val text = myFixture.editor.document.text
        val method = text.indexOf("public function testName(): void")
        assertTrue(text, method > text.indexOf("function adds") && method < text.lastIndexOf('}'))
        assertTrue("Formatted into the class body:\n$text", text.contains("\n    #[Test]\n    public function testName(): void\n"))
    }

    fun testItIsOfferedOnlyInATestoClass() {
        myFixture.configureByText("Calculator.php", "<?php\nnamespace App;\n\nfinal class Calculator\n{\n    public function add() {}<caret>\n}\n")

        assertFalse(TestoGenerateTestMethodAction().isValidForFile(project, myFixture.editor, myFixture.file))
    }

    fun testNewTestTakesTheNamespaceOfItsDirectory() {
        myFixture.addFileToProject("composer.json", """{"autoload-dev": {"psr-4": {"App\\Tests\\": "tests/"}}}""")
        val directory: PsiDirectory = checkNotNull(myFixture.addFileToProject("tests/Unit/.keep", "").containingDirectory)
        val newTest = checkNotNull(TestoFrameworkType.instance()!!.newTest)

        val file: PsiFile = checkNotNull(
            WriteCommandAction.writeCommandAction(project).compute<PsiFile?, RuntimeException> {
                PhpNewTestAction(newTest).createFile("CalcTest", newTest.templateName, directory)
            },
        )

        val text = file.text
        assertTrue(text, text.contains("namespace App\\Tests\\Unit;"))
        assertTrue(text, text.contains("use Testo\\Test;"))
        assertTrue(text, text.contains("final class CalcTest"))
    }
}
