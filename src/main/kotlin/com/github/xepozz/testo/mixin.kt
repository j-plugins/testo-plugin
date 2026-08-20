package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpAttributesOwner
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.PhpIndex

private val LOG = Logger.getInstance("#com.github.xepozz.testo.mixin")

fun PsiElement.isTestoExecutable() = isTestoFunction() || isTestoMethod() || isTestoBench()

fun PsiElement.isTestoBench() = when(this) {
    is Method -> hasAnyAttribute(*TestoClasses.BENCH_ATTRIBUTES)
    else -> false
}

fun PsiElement.isTestoFunction() = when(this) {
    is Function -> hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
    else -> false
}

fun PsiElement.isTestoMethod(resolveSubclasses: Boolean = true) = when (this) {
    is Method -> hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
            || (modifier.isPublic && name.startsWith("test"))
            || isPublicMethodOfTestoMarkedClass(resolveSubclasses)
    else -> false
}

// A public static method is a data provider (see isTestoDataProviderLike), and a #[Bench] method is a benchmark — both
// live in test-marked classes without being tests themselves, and running either as `--type=test` would be wrong.
private fun Method.isPublicMethodOfTestoMarkedClass(resolveSubclasses: Boolean) = when {
    !modifier.isPublic -> false
    modifier.isAbstract -> false
    modifier.isStatic -> false
    name.startsWith("__") -> false
    isTestoBench() -> false
    else -> {
        val cls = containingClass
        when {
            cls == null -> false
            cls.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES) -> true
            cls.isAbstract -> resolveSubclasses && hasTestoSubclass(cls)
            else -> false
        }
    }
}

private fun hasTestoSubclass(cls: PhpClass): Boolean {
    if (DumbService.isDumb(cls.project)) return false
    return PhpIndex.getInstance(cls.project).getAllSubclasses(cls.fqn).any { sub ->
        TestoTestDescriptor.isTestClassName(sub.name)
            || sub.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
            || sub.hasAnyAttribute(*TestoClasses.TEST_CASE_ATTRIBUTES)
    }
}

fun PsiElement.isTestoDataProviderLike() = when (this) {
    is Method -> modifier.isPublic && modifier.isStatic
    is Function -> true
    else -> false
}

fun PhpAttributesOwner.hasAttribute(fqn: String) = getAttributes(fqn).isNotEmpty()
fun PhpAttributesOwner.hasAnyAttribute(vararg fqn: String) = attributes.any { it.fqn in fqn }

fun PsiElement.isTestoClass(resolveSubclasses: Boolean = true) = when (this) {
    is PhpClass -> TestoTestDescriptor.isTestClassName(name)
            || hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
            || isTestoCaseClass()
            || ownMethods.any { it.isTestoMethod(resolveSubclasses) || it.isTestoBench() }
    else -> false
}

/**
 * A class that a class-level attribute turns into a test case on its own (currently `#[TestRectorFixtures]`). The tests
 * of such a case are synthesized by the framework, so — unlike a class carrying `#[Test]` — its own public methods must
 * not be treated as tests.
 */
fun PsiElement.isTestoCaseClass() = when (this) {
    is PhpClass -> hasAnyAttribute(*TestoClasses.TEST_CASE_ATTRIBUTES)
    else -> false
}

fun PsiFile.isTestoFile(): Boolean {
    if (this !is PhpFile) return false
    val vFile = virtualFile ?: return false
    if (!vFile.isValid) return false

    val fileIndex = ProjectFileIndex.getInstance(project)
    if (!fileIndex.isInContent(vFile)) return false
    if (fileIndex.isExcluded(vFile)) return false
    if (fileIndex.isUnderIgnored(vFile)) return false

    if (TestoTestDescriptor.isTestClassName(name.substringBeforeLast("."))) return true
    if (DumbService.isDumb(project)) return false

    return try {
        isTestoClassFile()
            || isTestoFunctionFile()
            || isTestBenchFile()
            || isTestoConfigFile()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("Failed to determine whether ${vFile.path} is a Testo file", e)
        false
    }
}

fun PhpFile.isTestoConfigFile() = PsiTreeUtil.findChildrenOfType(this, ClassReference::class.java)
    .any { it.parent is NewExpression && it.fqn == TestoClasses.APPLICATION_CONFIG }

fun PhpFile.isTestoClassFile() = PsiTreeUtil.findChildrenOfType(this, PhpClass::class.java)
    .any { it.isTestoClass() }

fun PhpFile.isTestoFunctionFile() = PsiTreeUtil.findChildrenOfType(this, Function::class.java)
    .any { it.isTestoFunction() }

fun PhpFile.isTestBenchFile() = PsiTreeUtil.findChildrenOfType(this, Function::class.java)
    .any { it.isTestoBench() }

fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean) = sequence {
    with(iterator()) {
        while (hasNext()) {
            val next = next()
            yield(next)
            if (!predicate(next)) break
        }
    }
}

fun <T> Collection<T>.takeWhileInclusive(predicate: (T) -> Boolean): Collection<T> =
    this.asSequence().takeWhileInclusive(predicate).toList()