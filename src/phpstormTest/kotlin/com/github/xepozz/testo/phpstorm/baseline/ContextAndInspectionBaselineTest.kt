package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.TestoContext
import com.github.xepozz.testo.phpstorm.tests.inspections.TestoGroupNameInspection
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.TemplateContextTypes
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Where the Testo live-template context applies, and how the group-name inspection presents itself: the parts of
 * their behaviour that come from the base classes rather than from their own code.
 */
class ContextAndInspectionBaselineTest : BasePlatformTestCase() {

    fun testLiveTemplateContext() {
        val out = StringBuilder()
        val context = TemplateContextTypes.getByClass(TestoContext::class.java)
        out.appendLine("id=${context.contextId} presentable=${context.presentableName}")
        out.appendLine("base=${context.baseContextType?.contextId}")
        for ((name, text) in SAMPLES) {
            val file = myFixture.configureByText(name, text.trimIndent())
            val caret = myFixture.caretOffset
            val actionContext = TemplateActionContext.create(file, myFixture.editor, caret, caret, false)
            out.appendLine("$name: ${text.trimIndent().lines().first { "<caret>" in it }.trim()} -> ${context.isInContext(actionContext)}")
        }
        Baselines.assertMatches("live-template-context", out.toString())
    }

    fun testGroupNameInspectionPresentation() {
        val ep = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.shortName == "TestoGroupNameInspection" }
        val wrapper = LocalInspectionToolWrapper(ep)
        val tool = wrapper.tool as TestoGroupNameInspection
        val out = StringBuilder()
        out.appendLine("shortName=${wrapper.shortName}")
        out.appendLine("displayName=${wrapper.displayName}")
        out.appendLine("groupPath=${wrapper.groupPath.joinToString(" / ")}")
        out.appendLine("language=${wrapper.language}")
        out.appendLine("defaultLevel=${wrapper.defaultLevel}")
        out.appendLine("enabledByDefault=${wrapper.isEnabledByDefault}")
        out.appendLine("toolGroupDisplayName=${tool.groupDisplayName}")
        out.appendLine("toolGroupPath=${tool.groupPath.joinToString(" / ")}")
        out.appendLine("toolDefaultLevel=${tool.defaultLevel}")

        myFixture.enableInspections(tool)
        val file = myFixture.configureByText(
            "GroupsTest.php",
            """
            <?php
            namespace App;

            final class GroupsTest
            {
                #[\Testo\Filter\Group(' padded ')]
                public function testA(): void {}

                /** @noinspection TestoGroupNameInspection */
                #[\Testo\Filter\Group('!excluded')]
                public function testB(): void {}

                #[\Testo\Filter\Group]
                public function testC(): void {}
            }
            """.trimIndent(),
        )
        for (highlight in myFixture.doHighlighting().filter { it.inspectionToolId == "TestoGroupNameInspection" }) {
            out.appendLine("problem ${highlight.severity} '${file.text.substring(highlight.startOffset, highlight.endOffset)}': ${highlight.description}")
        }
        val suppressed = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiElement::class.java)
            .firstOrNull { it.text == "'!excluded'" }
            ?.let { tool.isSuppressedFor(it) }
        out.appendLine("suppressedByNoinspection=$suppressed")
        // Sorted: the order is the platform's, and it differs between 252 and 262.
        out.appendLine("suppressActions=${tool.getBatchSuppressActions(file).map { it.familyName }.sorted()}")
        Baselines.assertMatches("group-name-inspection", out.toString())
    }

    private companion object {
        val SAMPLES = listOf(
            "InBodyTest.php" to """
                <?php
                final class InBodyTest
                {
                    <caret>
                }
            """,
            "BeforeBraceTest.php" to """
                <?php
                final class BeforeBraceTest <caret>
                {
                }
            """,
            "InMethodTest.php" to """
                <?php
                final class InMethodTest
                {
                    public function testA(): void { <caret> }
                }
            """,
            "InCommentTest.php" to """
                <?php
                final class InCommentTest
                {
                    // <caret>
                }
            """,
            "InStringTest.php" to """
                <?php
                final class InStringTest
                {
                    public const A = '<caret>';
                }
            """,
            "Service.php" to """
                <?php
                final class Service
                {
                    <caret>
                }
            """,
            "html.php" to """
                <div><caret></div>
                <?php final class HtmlTest {}
            """,
        )
    }
}
