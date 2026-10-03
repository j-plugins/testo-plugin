package com.github.xepozz.testo.phpstorm.baseline

import com.github.xepozz.testo.baseline.BaselineFixture
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.TestoTestLocator
import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.phpunit.LocationInfo
import com.jetbrains.php.testFramework.PhpTestFrameworkConfigurationIml
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import com.jetbrains.php.util.pathmapper.PhpPathMapper

/**
 * Where every gutter icon points, and how a test address is read back.
 *
 * The address a leaf gets is computed by the provider's two private readers (`getInfoIdentifier` for names,
 * `getInfoKeyword` for `yield`/`return`); the baseline reaches them by name, which is why they keep it.
 */
class LocationBaselineTest : BasePlatformTestCase() {
    private lateinit var projectFixture: BaselineFixture
    private val provider = TestoTestRunLineMarkerProvider()

    override fun setUp() {
        super.setUp()
        projectFixture = BaselineFixture(myFixture).also { it.setUp() }
        val configuration = PhpTestFrameworkConfigurationIml(TestoFrameworkType.INSTANCE)
        configuration.executablePath = "/opt/testo/bin/testo"
        PhpTestFrameworkSettingsManager.getInstance(project)
            .addSettingsIfAbsent(TestoFrameworkType.INSTANCE, configuration, null, null)
    }

    override fun tearDown() {
        try {
            BaselineSettings.clearFrameworkSettings(project)
            projectFixture.tearDown()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun address(leaf: PsiElement): String? {
        val reader = if (leaf.text == "yield" || leaf.text == "return") "getInfoKeyword" else "getInfoIdentifier"
        val method = TestoTestRunLineMarkerProvider::class.java.getDeclaredMethod(reader, PsiElement::class.java)
        method.isAccessible = true
        return method.invoke(provider, leaf) as String?
    }

    private fun position(file: PsiFile, leaf: PsiElement): String {
        val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        val line = document.getLineNumber(leaf.textOffset)
        return "${line + 1}:${leaf.textOffset - document.getLineStartOffset(line) + 1}"
    }

    fun testGutterAddresses() {
        val out = StringBuilder()
        for ((path, file) in projectFixture.files.toSortedMap()) {
            out.appendLine("== $path")
            for (leaf in PsiTreeUtil.collectElements(file) { it.firstChild == null }) {
                if (leaf.text.isBlank()) continue
                val info = provider.getInfo(leaf) ?: continue
                val url = address(leaf)
                out.appendLine("  ${position(file, leaf)} ${leaf.text} -> ${url} actions=${info.actions.size}")
            }
        }
        Baselines.assertMatches("gutter", out.toString())
    }

    fun testAddressParsingAndNavigation() {
        val locator = TestoTestLocator(PhpPathMapper.create(emptyList()))
        val out = StringBuilder()
        out.appendLine("== parsing")
        for (link in LINKS) {
            val info = locator.getLocationInfo(link)
            out.appendLine("  $link -> ${info?.let { "class=${it.className} method=${it.methodName} file=${it.file?.path}" }}")
        }
        out.appendLine("== navigation")
        val calc = projectFixture.files.getValue("tests/CalcTest.php").virtualFile
        val functions = projectFixture.files.getValue("tests/functions.php").virtualFile
        val targets = listOf(
            Triple(calc, "\\App\\CalcTest", "median"),
            Triple(calc, "\\App\\CalcTest", "med:3:0"),
            Triple(calc, "\\App\\CalcTest", null),
            Triple(calc, "\\App\\Missing", null),
            Triple(calc, null, null),
            Triple(functions, "\\App\\medianOf", null),
            Triple(functions, "\\App\\medianOf:0:1", null),
        )
        for ((file, className, methodName) in targets) {
            val parsedClass = className?.let { locator.getLocationInfo("p.php::$it")?.className }
            val parsedMethod = methodName?.let { locator.getLocationInfo("p.php::C::$it")?.methodName }
            val store = findElement.invoke(locator, LocationInfo(parsedClass, parsedMethod, file), project)
            val found = store?.let { describeStore(it) }
            out.appendLine("  ${file.name}::$className::$methodName -> $found")
        }
        Baselines.assertMatches("locations", out.toString())
    }

    // Protected on the locator; the baseline reads what it answers for a parsed address.
    private val findElement = TestoTestLocator::class.java
        .getDeclaredMethod("findElement", LocationInfo::class.java, com.intellij.openapi.project.Project::class.java)
        .also { it.isAccessible = true }

    private fun describeStore(store: Any): String = generateSequence<Class<*>>(store.javaClass) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filter { PsiElement::class.java.isAssignableFrom(it.type) }
        .sortedBy { it.name }
        .joinToString(" ") { field ->
            field.isAccessible = true
            "${field.name}=${BaselineSettings.describe(field.get(store) as PsiElement?)}"
        }

    private companion object {
        val LINKS = listOf(
            "path/to/file.php",
            "path/to/file.php::\\Ns\\CalcTest",
            "path/to/file.php::\\Ns\\CalcTest::median",
            "path/to/file.php::\\Ns\\CalcTest::med:3:0",
            "path/to/file.php::\\Ns\\CalcTest::med#2",
            "path/to/file.php::\\Ns\\CalcTest::med with data set #1",
            "path/to/file.php::\\Ns\\medianOf",
            "path/to/file.php::\\Ns\\medianOf:0:1",
            "D:/p/Calculator.php::\\Ns\\Calculator::med:3:0",
            "a::b::c::d",
            "",
        )
    }
}
