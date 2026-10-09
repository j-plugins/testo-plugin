package com.github.xepozz.testo.baseline

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * One small Testo project covering every kind of context a run can start from: test methods and functions, data
 * attributes, providers and their `yield`s, benchmarks, inline tests, groups, class-level attributes, an abstract case,
 * the Rector bridge, configuration objects, a PHPUnit test and a plain class.
 *
 * The files live under a test source root: data providers are looked up in the project's test scope.
 */
class BaselineFixture(private val fixture: CodeInsightTestFixture) {
    lateinit var testsRoot: VirtualFile
        private set

    val files = mutableMapOf<String, PsiFile>()

    fun setUp() {
        for ((path, text) in FILES) files[path] = fixture.addFileToProject(path, text.trimIndent() + "\n")
        testsRoot = files.getValue("tests/CalcTest.php").virtualFile.parent
        PsiTestUtil.addSourceRoot(fixture.module, testsRoot, true)
    }

    fun tearDown() {
        PsiTestUtil.removeSourceRoot(fixture.module, testsRoot)
    }

    /** The leaf at the [occurrence]-th match of [anchor] in [path], shifted by [shift] characters into the match. */
    fun leaf(path: String, anchor: String, occurrence: Int = 0, shift: Int = 0): PsiElement {
        val file = files.getValue(path)
        var offset = -1
        repeat(occurrence + 1) {
            offset = file.text.indexOf(anchor, offset + 1)
            check(offset >= 0) { "No occurrence #$occurrence of '$anchor' in $path" }
        }
        return checkNotNull(file.findElementAt(offset + shift)) { "No leaf at '$anchor' in $path" }
    }

    /** Every kind of context a run can start from, labelled; the producer baselines walk them in this order. */
    fun contexts(): List<Pair<String, PsiElement>> {
        val f = this
        val calc = "tests/CalcTest.php"
        return listOf(
            "file:CalcTest" to f.files.getValue(calc),
            "class:CalcTest" to f.leaf(calc, "class CalcTest", shift = 6),
            "method:adds" to f.leaf(calc, "function adds", shift = 9),
            "attr:Test@adds" to f.leaf(calc, "#[Test]", shift = 2),
            "attr:Test@median" to f.leaf(calc, "#[Test]", occurrence = 1, shift = 2),
            "method:median" to f.leaf(calc, "function median", shift = 9),
            "method:providedWithoutTest" to f.leaf(calc, "function providedWithoutTest", shift = 9),
            "attr:DataProvider@providedWithoutTest" to f.leaf(calc, "#[DataProvider('cases')]", occurrence = 1, shift = 2),
            "attr:DataProvider#0@median" to f.leaf(calc, "#[DataProvider('cases')]", shift = 2),
            "attr:DataProvider#1@median" to f.leaf(calc, "#[DataProvider('moreCases')]", shift = 2),
            "arg:DataProvider#0@median" to f.leaf(calc, "'cases'", shift = 1),
            "provider:cases" to f.leaf(calc, "function cases", shift = 9),
            "yield:cases#0" to f.leaf(calc, "yield [1]"),
            "yield:cases#1" to f.leaf(calc, "yield [2]"),
            "return:cases" to f.leaf(calc, "return [];"),
            "yield:moreCases#0" to f.leaf(calc, "yield [3]"),
            "provider:unusedProvider" to f.leaf(calc, "function unusedProvider", shift = 9),
            "yield:unusedProvider#0" to f.leaf(calc, "yield [4]"),
            "method:sumBench" to f.leaf(calc, "function sumBench", shift = 9),
            "attr:Bench@sumBench" to f.leaf(calc, "#[Bench]", shift = 2),
            "method:inlineSum" to f.leaf(calc, "function inlineSum", shift = 9),
            "attr:TestInline#0@inlineSum" to f.leaf(calc, "#[TestInline([1, 2])]", shift = 2),
            "attr:TestInline#1@inlineSum" to f.leaf(calc, "#[TestInline([3, 4])]", shift = 2),
            "method:testGrouped" to f.leaf(calc, "function testGrouped", shift = 9),
            "attr:Group@testGrouped" to f.leaf(calc, "#[Group('db', 'slow')]", shift = 2),
            "attr:Group(empty)@testUngrouped" to f.leaf(calc, "#[Group]", shift = 2),
            "method:helper(private)" to f.leaf(calc, "function helper", shift = 9),
            "provider:shared" to f.leaf("tests/SharedProviderTest.php", "function shared", shift = 9),
            "yield:shared#0" to f.leaf("tests/SharedProviderTest.php", "yield [1]"),
            "method:first" to f.leaf("tests/SharedProviderTest.php", "function first", shift = 9),
            "class:CaseTest" to f.leaf("tests/CaseTest.php", "class CaseTest", shift = 6),
            "classAttr:Test@CaseTest" to f.leaf("tests/CaseTest.php", "#[\\Testo\\Test]", shift = 9),
            "method:itWorks" to f.leaf("tests/CaseTest.php", "function itWorks", shift = 9),
            "method:benchIt" to f.leaf("tests/CaseTest.php", "function benchIt", shift = 9),
            "class:SomeRector" to f.leaf("tests/Rector/SomeRector.php", "class SomeRector", shift = 6),
            "classAttr:TestRectorFixtures@SomeRector" to f.leaf("tests/Rector/SomeRector.php", "TestRectorFixtures("),
            "method:refactor" to f.leaf("tests/Rector/SomeRector.php", "function refactor", shift = 9),
            "class:AbstractBaseTest" to f.leaf("tests/Base/AbstractBaseTest.php", "class AbstractBaseTest", shift = 6),
            "method:inherited" to f.leaf("tests/Base/AbstractBaseTest.php", "function inherited", shift = 9),
            "class:ConcreteTest" to f.leaf("tests/Base/ConcreteTest.php", "class ConcreteTest", shift = 6),
            "function:medianOf" to f.leaf("tests/functions.php", "function medianOf", shift = 9),
            "attr:Test@medianOf" to f.leaf("tests/functions.php", "#[\\Testo\\Test]\nfunction medianOf", shift = 9),
            "attr:Group@groupedFunction" to f.leaf("tests/functions.php", "Group('fn')"),
            "function:helperFunction" to f.leaf("tests/functions.php", "function helperFunction", shift = 9),
            "class:UserTest" to f.leaf("tests/UserTest.php", "class UserTest", shift = 6),
            "method:testSomething@UserTest" to f.leaf("tests/UserTest.php", "function testSomething", shift = 9),
            "method:testSomething@PhpUnit" to f.leaf("tests/PhpUnitStyleTest.php", "function testSomething", shift = 9),
            "new:ApplicationConfig" to f.leaf("testo.php", "new ApplicationConfig", shift = 4),
            "new:SuiteConfig('Unit')" to f.leaf("testo.php", "new SuiteConfig('Unit')", shift = 4),
            "new:SuiteConfig(\$dynamic)" to f.leaf("testo.php", "new SuiteConfig(\$dynamic)", shift = 4),
            "file:testo.php" to f.leaf("testo.php", "\$dynamic = "),
            "class:Calculator(non-test)" to f.leaf("src/Calculator.php", "class Calculator", shift = 6),
            "dir:tests" to directory(f.testsRoot.path),
        )
    }

    private fun directory(path: String): PsiDirectory {
        val vf = checkNotNull(fixture.findFileInTempDir(path.substringAfter("/src/"))) { "No directory $path" }
        return checkNotNull(PsiManager.getInstance(fixture.project).findDirectory(vf))
    }

    companion object {
        val FILES: List<Pair<String, String>> = listOf(
            "tests/CalcTest.php" to """
                <?php
                namespace App;

                use Testo\Test;
                use Testo\Bench;
                use Testo\Data\DataProvider;
                use Testo\Inline\TestInline;
                use Testo\Filter\Group;

                final class CalcTest
                {
                    #[Test]
                    public function adds(): void {}

                    #[Test]
                    #[DataProvider('cases')]
                    #[DataProvider('moreCases')]
                    public function median(int ${'$'}a): void {}

                    #[DataProvider('cases')]
                    public function providedWithoutTest(int ${'$'}a): void {}

                    public static function cases(): iterable
                    {
                        yield [1];
                        yield [2];
                        return [];
                    }

                    public static function moreCases(): iterable
                    {
                        yield [3];
                    }

                    public static function unusedProvider(): iterable
                    {
                        yield [4];
                    }

                    #[Bench]
                    public function sumBench(): void {}

                    #[TestInline([1, 2])]
                    #[TestInline([3, 4])]
                    public static function inlineSum(int ${'$'}a, int ${'$'}b): int { return ${'$'}a + ${'$'}b; }

                    #[Group('db', 'slow')]
                    public function testGrouped(): void {}

                    #[Group]
                    public function testUngrouped(): void {}

                    private function helper(): void {}
                }
            """,
            "tests/SharedProviderTest.php" to """
                <?php
                namespace App;

                use Testo\Data\DataProvider;

                final class SharedProviderTest
                {
                    #[\Testo\Test]
                    #[DataProvider('shared')]
                    public function first(int ${'$'}x): void {}

                    #[\Testo\Test]
                    #[DataProvider('shared')]
                    public function second(int ${'$'}x): void {}

                    public static function shared(): iterable
                    {
                        yield [1];
                    }
                }
            """,
            "tests/CaseTest.php" to """
                <?php
                namespace App;

                #[\Testo\Test]
                final class CaseTest
                {
                    public function itWorks(): void {}

                    #[\Testo\Bench]
                    public function benchIt(): void {}
                }
            """,
            "tests/Rector/SomeRector.php" to """
                <?php
                namespace App\Rector;

                #[\Testo\Bridge\Rector\Testing\TestRectorFixtures('SomeRector')]
                final class SomeRector
                {
                    public function refactor(): void {}
                }
            """,
            "tests/Base/AbstractBaseTest.php" to """
                <?php
                namespace App\Base;

                abstract class AbstractBaseTest
                {
                    #[\Testo\Test]
                    public function inherited(): void {}
                }
            """,
            "tests/Base/ConcreteTest.php" to """
                <?php
                namespace App\Base;

                final class ConcreteTest extends AbstractBaseTest {}
            """,
            "tests/functions.php" to """
                <?php
                namespace App;

                #[\Testo\Test]
                function medianOf(): void {}

                #[\Testo\Filter\Group('fn')]
                #[\Testo\Test]
                function groupedFunction(): void {}

                function helperFunction(): void {}
            """,
            "tests/UserTest.php" to """
                <?php
                class UserTest
                {
                    public function testSomething(): void {}
                }
            """,
            "tests/PhpUnitStyleTest.php" to """
                <?php
                final class PhpUnitStyleTest extends \PHPUnit\Framework\TestCase
                {
                    public function testSomething(): void {}
                }
            """,
            "stubs/TestCase.php" to """
                <?php
                namespace PHPUnit\Framework;

                abstract class TestCase {}
            """,
            "testo.php" to """
                <?php
                use Testo\Application\Config\ApplicationConfig;
                use Testo\Application\Config\SuiteConfig;

                ${'$'}dynamic = 'Dynamic';

                return new ApplicationConfig(
                    suites: [
                        new SuiteConfig('Unit'),
                        new SuiteConfig(${'$'}dynamic),
                    ],
                );
            """,
            "src/Calculator.php" to """
                <?php
                namespace App;

                final class Calculator
                {
                    public function add(int ${'$'}a, int ${'$'}b): int { return ${'$'}a + ${'$'}b; }
                }
            """,
        )
    }
}
