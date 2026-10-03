package com.github.xepozz.testo.php

import com.github.xepozz.testo.launch.TestoConfiguration
import com.intellij.execution.Location
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * What the plugin reads from the PHP plugin of the IDE it runs in: PHP code, the class index, how the interpreter sees
 * paths, and whether Testo is set up. The rest of the plugin reaches PHP only through here, so it compiles and loads
 * against any PHP plugin that provides an implementation.
 *
 * Exactly one implementation is registered, as an application service, by the PHP-specific descriptor
 * (`META-INF/testo-php.xml`).
 */
interface TestoPhp {
    /** Captures the selected configuration's PHP environment for a chain of Testo and tool processes. */
    fun toolEnvironment(configuration: TestoConfiguration): TestoToolEnvironment

    /** Complete statement range and the first PSI fragment, whose preceding comments belong to the statement. */
    fun statementAt(leaf: PsiElement?, document: Document): TestoStatementTarget?

    fun isPhpFile(file: PsiFile?): Boolean

    /** The kind of [leaf] when it is one a run icon can sit on, otherwise null. */
    fun leafKind(leaf: PsiElement): PhpLeafKind?

    /** [element] as one of the PHP constructs the plugin reads, or null for anything else. */
    fun view(element: PsiElement?): PhpElementView?

    fun topLevelClasses(file: PsiFile): List<PhpClassView>

    fun topLevelFunctions(file: PsiFile): List<PhpFunctionView>

    /** Every class of the file, including those declared inside `namespace { }` blocks, in the PHP plugin's order. */
    fun allClasses(file: PsiFile): List<PhpClassView>

    fun attributesIn(file: PsiFile): List<PhpAttributeView>

    fun classReferencesIn(file: PsiFile): List<PhpClassReferenceView>

    /** Whether [element] is a `yield` or a `return`. */
    fun isExitStatement(element: PsiElement): Boolean

    /** Whether [element] is a `yield`. */
    fun isYield(element: PsiElement): Boolean

    /** How many `yield`/`return` keywords come before [leaf] among its siblings. */
    fun precedingExitKeywords(leaf: PsiElement): Int

    /** Whether a function the method reference [call] resolves to declares `@throws` [exceptionFqn]. */
    fun callThrows(call: PsiElement?, exceptionFqn: String): Boolean

    /** Classes by fully qualified name (`\App\FooTest`). Not to be asked in dumb mode or from an indexer. */
    fun classesByFqn(project: Project, fqn: String): Collection<PhpClassView>

    /** Every subclass of the class named [fqn]. Not to be asked in dumb mode or from an indexer. */
    fun allSubclasses(project: Project, fqn: String): Collection<PhpClassView>

    /** Whether a superclass of [cls], not [cls] itself, satisfies [predicate]. Not to be asked in dumb mode. */
    fun anySuperClass(cls: PhpClassView, predicate: (PhpClassView) -> Boolean): Boolean

    /** How the project's default interpreter sees the project's files. */
    fun projectPaths(project: Project): TestoPathMapping

    /** Whether Testo is set up in [project]: it has a Testo configuration that is remote or names an executable. */
    fun isTestoConfigured(project: Project): Boolean

    /** The factory Testo run configurations are created with; what it creates is a [TestoConfiguration]. */
    fun configurationFactory(): ConfigurationFactory

    /**
     * Where a failed results-tree node should lead when its location already names one data set of something that is
     * not a method; null leaves the choice to the stack trace.
     */
    fun dataSetNavigatable(location: Location<*>): Navigatable?

    /**
     * The interpreter → local translations a coverage report's source paths may need: a report written in a
     * container or WSL names files by the interpreter's paths. Taken from the project, not from a run, since a suite
     * is also loaded from a manual import or after a restart.
     */
    fun coverageSourcePathMappers(project: Project): List<(String) -> String?>

    companion object {
        fun getInstance(): TestoPhp = ApplicationManager.getApplication().service()
    }
}

enum class PhpLeafKind { IDENTIFIER, YIELD, RETURN }

class TestoStatementTarget(val range: TextRange, val anchor: PsiElement)

/** Paths between the IDE's machine and the environment an interpreter runs in (a container, a remote host, WSL). */
interface TestoPathMapping {
    /** [localPath] as the interpreter sees it, or unchanged when no mapping applies. */
    fun toEnvironment(localPath: String): String

    /** A path the interpreter reported, as a local file, or null when there is no such file. */
    fun toLocalFile(path: String): VirtualFile?

    /** A path the interpreter reported, as a local path, whether or not the file exists yet. */
    fun toLocalPath(path: String): String?
}

/**
 * A PHP construct. Views compare equal when they wrap the same [psi], and every property is read from the PSI on
 * access, so a view is as current as the element it wraps.
 */
interface PhpElementView {
    val psi: PsiElement
}

interface PhpDeclarationView : PhpElementView {
    val name: String

    val nameIdentifier: PsiElement?

    /** In source order. */
    val attributes: List<PhpAttributeView>

    fun attributes(fqn: String): List<PhpAttributeView>
}

interface PhpClassView : PhpDeclarationView {
    /** With the leading backslash: `\App\FooTest`. */
    val fqn: String

    val isAbstract: Boolean

    val isFinal: Boolean

    /** In source order. */
    val ownMethods: List<PhpFunctionView>

    fun findOwnMethod(name: String): PhpFunctionView?

    /** A method this class declares or inherits. */
    fun findMethod(name: String): PhpFunctionView?

    /** Where the class body opens, or null when the parser found no body. */
    val bodyStartOffset: Int?
}

/** A free function or a method. */
interface PhpFunctionView : PhpDeclarationView {
    /**
     * For a free function, its fully qualified name (`\Ns\medianOf`). A method's is in whatever form the PHP plugin
     * uses, so it is never compared with anything but another function's.
     */
    val fqn: String

    val isMethod: Boolean

    val containingClass: PhpClassView?

    val isPublic: Boolean

    val isStatic: Boolean

    val isAbstract: Boolean
}

interface PhpAttributeView : PhpElementView {
    val fqn: String?

    /** The class, function or method the attribute belongs to. */
    val owner: PsiElement?

    /** The reference naming the attribute's class. */
    val nameReference: PsiElement?

    /** The argument expressions as written. */
    val parameters: List<PsiElement>

    /** The contents of [parameter] when it is a string literal, otherwise null. */
    fun stringContents(parameter: PsiElement): String?

    /**
     * The argument passed by [name], or else at [index], read the way the PHP plugin stores it: without loading other
     * files, so an indexer may ask. Null when there is no such argument or it is not a constant expression.
     */
    fun argument(name: String?, index: Int): PhpArgumentText?

    /** Every argument in order, read like [argument]; null stands for one that is not a constant expression. */
    val arguments: List<PhpArgumentText?>
}

/** An attribute argument as written: `'cases'`, `[self::class, 'cases']`. */
data class PhpArgumentText(val text: String, val isStringLiteral: Boolean)

interface PhpClassReferenceView : PhpElementView {
    val fqn: String?

    /** The `new` expression this reference names the class of, if it does. */
    val newExpression: PhpNewExpressionView?

    /** The attribute this reference names the class of, if it does. */
    val attribute: PhpAttributeView?
}

interface PhpNewExpressionView : PhpElementView {
    val classFqn: String?

    /** The contents of the first argument when it is a string literal. */
    val firstStringArgument: String?
}
