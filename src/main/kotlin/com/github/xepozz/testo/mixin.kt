package com.github.xepozz.testo

import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpDeclarationView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

private val LOG = Logger.getInstance("#com.github.xepozz.testo.mixin")

private val php: TestoPhp get() = TestoPhp.getInstance()

/** A class name Testo treats as a test case by convention: `…Test` or `…TestBase`. */
fun isTestoTestClassName(name: String) = name.endsWith("Test") || name.endsWith("TestBase")

fun PsiElement.isTestoExecutable() = isTestoFunction() || isTestoMethod() || isTestoBench()

fun PsiElement.isTestoBench(): Boolean {
    val function = php.view(this) as? PhpFunctionView ?: return false
    return function.isMethod && function.hasAnyAttribute(*TestoClasses.BENCH_ATTRIBUTES)
}

fun PsiElement.isTestoFunction(): Boolean {
    val function = php.view(this) as? PhpFunctionView ?: return false
    return function.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
}

fun PsiElement.isTestoMethod(resolveHierarchy: Boolean = true): Boolean {
    val method = (php.view(this) as? PhpFunctionView)?.takeIf { it.isMethod } ?: return false
    return method.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
        || (method.isPublic && method.name.startsWith("test"))
        || method.isPublicMethodOfTestoMarkedClass(resolveHierarchy)
}

// A public static method is a data provider (see isTestoDataProviderLike), and a #[Bench] method is a benchmark — both
// live in test-marked classes without being tests themselves, and running either as `--type=test` would be wrong.
private fun PhpFunctionView.isPublicMethodOfTestoMarkedClass(resolveHierarchy: Boolean) = when {
    !isPublic -> false
    isAbstract -> false
    isStatic -> false
    name.startsWith("__") -> false
    psi.isTestoBench() -> false
    else -> {
        val cls = containingClass
        when {
            cls == null -> false
            cls.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES) -> true
            resolveHierarchy && cls.hasTestoAncestor() -> true
            cls.isAbstract -> resolveHierarchy && hasTestoSubclass(cls)
            else -> false
        }
    }
}

private fun hasTestoSubclass(cls: PhpClassView): Boolean {
    val project = cls.psi.project
    if (DumbService.isDumb(project)) return false
    return php.allSubclasses(project, cls.fqn).any { sub ->
        isTestoTestClassName(sub.name)
            || sub.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
            || sub.hasAnyAttribute(*TestoClasses.TEST_CASE_ATTRIBUTES)
    }
}

// #[Test] on a base class marks every inheritor a case, even one declaring no marker of its own.
private fun PhpClassView.hasTestoAncestor(): Boolean {
    if (DumbService.isDumb(psi.project)) return false
    return php.anySuperClass(this) { it.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES) }
}

fun PsiElement.isTestoDataProviderLike(): Boolean {
    val function = php.view(this) as? PhpFunctionView ?: return false
    return !function.isMethod || (function.isPublic && function.isStatic)
}

fun PsiElement.hasAttribute(fqn: String) = (php.view(this) as? PhpDeclarationView)?.attributes(fqn)?.isNotEmpty() == true

fun PsiElement.hasAnyAttribute(vararg fqn: String) = (php.view(this) as? PhpDeclarationView)?.hasAnyAttribute(*fqn) == true

fun PhpDeclarationView.hasAnyAttribute(vararg fqn: String) = attributes.any { it.fqn in fqn }

fun PsiElement.isTestoClass(resolveHierarchy: Boolean = true): Boolean {
    val cls = php.view(this) as? PhpClassView ?: return false
    return isTestoTestClassName(cls.name)
        || cls.hasAnyAttribute(*TestoClasses.TEST_ATTRIBUTES)
        || cls.psi.isTestoCaseClass()
        || cls.ownMethods.any { it.psi.isTestoMethod(resolveHierarchy) || it.psi.isTestoBench() }
        || (resolveHierarchy && cls.hasTestoAncestor())
}

/**
 * A class that a class-level attribute turns into a test case on its own (currently `#[TestRectorFixtures]`). The tests
 * of such a case are synthesized by the framework, so — unlike a class carrying `#[Test]` — its own public methods must
 * not be treated as tests.
 */
fun PsiElement.isTestoCaseClass() =
    (php.view(this) as? PhpClassView)?.hasAnyAttribute(*TestoClasses.TEST_CASE_ATTRIBUTES) == true

fun PsiFile.isTestoFile(): Boolean {
    if (!php.isPhpFile(this)) return false
    val vFile = virtualFile ?: return false
    if (!vFile.isValid) return false

    val fileIndex = ProjectFileIndex.getInstance(project)
    if (!fileIndex.isInContent(vFile)) return false
    if (fileIndex.isExcluded(vFile)) return false
    if (fileIndex.isUnderIgnored(vFile)) return false

    if (isTestoTestClassName(name.substringBeforeLast("."))) return true
    if (DumbService.isDumb(project)) return false

    return try {
        isTestoClassFile()
            || isTestoFunctionFile()
            || isTestoConfigFile()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Throwable) {
        LOG.warn("Failed to determine whether ${vFile.path} is a Testo file", e)
        false
    }
}

// No AST walks unless unavoidable: isTestoFile runs for every file the project view paints, and loading an AST behind
// a stale stub index is what the platform reports as "Outdated stub in index".
fun PsiFile.isTestoConfigFile() = viewProvider.contents.contains(APPLICATION_CONFIG_SHORT_NAME)
    && php.classReferencesIn(this).any { it.newExpression != null && it.fqn == TestoClasses.APPLICATION_CONFIG }

private val APPLICATION_CONFIG_SHORT_NAME = TestoClasses.APPLICATION_CONFIG.substringAfterLast('\\')

fun PsiFile.topLevelClasses(): List<PhpClassView> = php.topLevelClasses(this)

fun PsiFile.isTestoClassFile() = topLevelClasses().any { it.psi.isTestoClass() }

fun PsiFile.isTestoFunctionFile() = php.topLevelFunctions(this).any { it.psi.isTestoFunction() }

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
