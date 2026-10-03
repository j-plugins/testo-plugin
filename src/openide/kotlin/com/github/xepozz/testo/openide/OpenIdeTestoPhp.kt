package com.github.xepozz.testo.openide

import com.github.xepozz.testo.openide.tests.TestoFrameworkType
import com.github.xepozz.testo.openide.tests.run.TestoRunConfigurationType
import com.github.xepozz.testo.php.PhpArgumentText
import com.github.xepozz.testo.php.PhpAttributeView
import com.github.xepozz.testo.php.PhpClassReferenceView
import com.github.xepozz.testo.php.PhpClassView
import com.github.xepozz.testo.php.PhpElementView
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.PhpLeafKind
import com.github.xepozz.testo.php.PhpNewExpressionView
import com.github.xepozz.testo.php.TestoPathMapping
import com.github.xepozz.testo.php.TestoPhp
import com.github.xepozz.testo.php.TestoToolEnvironment
import com.github.xepozz.testo.php.TestoStatementTarget
import com.github.xepozz.testo.launch.TestoConfiguration
import com.github.xepozz.testo.openide.tests.run.TestoRunConfiguration
import com.intellij.openapi.editor.Document
import com.intellij.execution.Location
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.ConcurrentFactoryMap
import ru.openide.openphp.lang.psi.symbols.PhpAttribute
import ru.openide.openphp.lang.psi.symbols.PhpAttributes
import ru.openide.openphp.lang.psi.symbols.PhpClassInfo
import ru.openide.openphp.lang.psi.symbols.PhpDeclarationKind
import ru.openide.openphp.lang.psi.symbols.PhpDeclarations
import ru.openide.openphp.lang.psi.symbols.PhpExitKind
import ru.openide.openphp.lang.psi.symbols.PhpFunctionKind
import ru.openide.openphp.lang.psi.symbols.PhpHierarchy
import ru.openide.openphp.lang.psi.symbols.PhpIndex
import ru.openide.openphp.lang.psi.symbols.PhpInstantiation
import ru.openide.openphp.lang.psi.symbols.PhpInstantiations
import ru.openide.openphp.lang.psi.symbols.PhpLiteral
import ru.openide.openphp.lang.psi.symbols.PhpLiterals
import ru.openide.openphp.lang.psi.symbols.PhpNames
import ru.openide.openphp.lang.psi.symbols.PhpSyntax
import ru.openide.openphp.lang.psi.symbols.PhpVisibility
import ru.openide.openphp.lang.psi.symbols.valueOrNull
import ru.openide.openphp.run.testing.PhpTestFrameworks
import ru.openide.openphp.settings.launch.PhpLaunchEnvironment
import ru.openide.openphp.settings.launch.PhpLaunchLocality
import java.util.concurrent.ConcurrentMap

/**
 * [TestoPhp] on the PHP for OpenIDE plugin, through its published API only. Where the shape of its PSI differs from
 * PhpStorm's, the answers follow PhpStorm, so the core decides the same on either:
 * - fully qualified names carry the leading backslash, and a name is accepted with or without it;
 * - a class member sits under the node of the class body, and that node answers as its class;
 * - closures, arrow functions and anonymous classes are functions and classes;
 * - of the segments of a written name (`\Testo\Filter\Group`) only the last is an identifier leaf.
 */
class OpenIdeTestoPhp : TestoPhp {
    override fun toolEnvironment(configuration: TestoConfiguration): TestoToolEnvironment =
        OpenIdeToolEnvironment(configuration as TestoRunConfiguration, TestoRunConfiguration.environmentProvider(configuration.project))

    override fun statementAt(leaf: PsiElement?, document: Document): TestoStatementTarget? {
        val file = leaf?.containingFile ?: return null
        val offset = leaf.textRange.startOffset
        var candidate: PsiElement = leaf
        while (candidate !is PsiFile) {
            val parent = candidate.parent ?: return null
            val children = parent.children.filter { PhpSyntax.isStatementContainerFor(parent, it) }
            val ranges = children.mapNotNull(PhpSyntax::statementRangeOf)
                .filter { it.startOffset <= offset && offset < it.endOffset }.distinct()
            if (ranges.size > 1) return null
            val range = ranges.singleOrNull()
            if (range != null) {
                val lineStart = document.getLineStartOffset(document.getLineNumber(range.startOffset))
                if (document.charsSequence.subSequence(lineStart, range.startOffset).isBlank()) {
                    val anchor = children.firstOrNull { it.textRange.startOffset == range.startOffset } ?: return null
                    return TestoStatementTarget(range, anchor)
                }
            }
            candidate = parent
        }
        return null
    }

    override fun isPhpFile(file: PsiFile?): Boolean = file?.language?.id == PHP_LANGUAGE

    override fun leafKind(leaf: PsiElement): PhpLeafKind? {
        when (PhpSyntax.exitKeywordKind(leaf)) {
            PhpExitKind.RETURN -> return PhpLeafKind.RETURN
            PhpExitKind.YIELD, PhpExitKind.YIELD_FROM -> return PhpLeafKind.YIELD
            null -> {}
        }
        val parent = leaf.parent ?: return null
        return PhpLeafKind.IDENTIFIER.takeIf { leaf.firstChild == null && PhpSyntax.nameLeafOf(parent) == leaf }
    }

    override fun view(element: PsiElement?): PhpElementView? = viewOf(element)

    override fun topLevelClasses(file: PsiFile): List<PhpClassView> =
        PhpDeclarations.inFile(file, topLevelOnly = true).filter(::isNamedClass).map(::OpenIdeClass)

    override fun topLevelFunctions(file: PsiFile): List<PhpFunctionView> =
        PhpDeclarations.inFile(file, topLevelOnly = true).filter(::isFreeFunction).map(::OpenIdeFunction)

    override fun allClasses(file: PsiFile): List<PhpClassView> =
        PhpDeclarations.inFile(file, topLevelOnly = false).filter(::isNamedClass).map(::OpenIdeClass)

    override fun attributesIn(file: PsiFile): List<PhpAttributeView> =
        PhpAttributes.inFile(file).map { OpenIdeAttribute(it.element) }

    override fun classReferencesIn(file: PsiFile): List<PhpClassReferenceView> =
        SyntaxTraverser.psiTraverser(file)
            .expand { it !is PsiComment }
            .filter(::isWrittenName)
            .map(::OpenIdeClassReference)
            .toList()

    override fun isExitStatement(element: PsiElement): Boolean = PhpSyntax.exitStatementKind(element) != null

    override fun isYield(element: PsiElement): Boolean = when (PhpSyntax.exitStatementKind(element)) {
        PhpExitKind.YIELD, PhpExitKind.YIELD_FROM -> true
        else -> false
    }

    override fun precedingExitKeywords(leaf: PsiElement): Int =
        generateSequence(leaf.prevSibling) { it.prevSibling }.count { PhpSyntax.exitKeywordKind(it) != null }

    override fun callThrows(call: PsiElement?, exceptionFqn: String): Boolean {
        val wanted = exceptionFqn.removePrefix("\\")
        return call?.references.orEmpty()
            .flatMap { reference ->
                if (reference is PsiPolyVariantReference) reference.multiResolve(false).mapNotNull { it.element }
                else listOfNotNull(reference.resolve())
            }
            .filter { PhpSyntax.functionKindOf(it) == PhpFunctionKind.METHOD }
            .any { wanted in PhpDeclarations.throwsOf(it).valueOrNull().orEmpty() }
    }

    override fun classesByFqn(project: Project, fqn: String): Collection<PhpClassView> =
        PhpIndex.classesByFqn(fqn, project, GlobalSearchScope.allScope(project)).map(::OpenIdeClass)

    // The core asks both hierarchy questions once per method of a class, and PHP for OpenIDE answers them by index
    // walks it does not cache; so both are cached here until the next PSI change.
    override fun allSubclasses(project: Project, fqn: String): Collection<PhpClassView> {
        val heirs = CachedValuesManager.getManager(project).getCachedValue(project, HEIRS_KEY, {
            val byName = ConcurrentFactoryMap.createMap<String, List<PsiElement>> { name ->
                PhpHierarchy.heirsOf(name, project, GlobalSearchScope.allScope(project))
            }
            CachedValueProvider.Result.create(byName, PsiModificationTracker.getInstance(project))
        }, false)
        return heirs.getValue(fqn.removePrefix("\\")).map(::OpenIdeClass)
    }

    // Superclasses only, as PhpStorm walks them: an interface a class implements is not its superclass.
    override fun anySuperClass(cls: PhpClassView, predicate: (PhpClassView) -> Boolean): Boolean {
        val psi = cls.psi
        return CachedValuesManager.getCachedValue(psi) {
            val superClasses = PhpHierarchy.superTypes(psi).types
                .filter { PhpDeclarations.classOf(it).valueOrNull()?.kind == PhpDeclarationKind.CLASS }
            CachedValueProvider.Result.create(superClasses, PsiModificationTracker.getInstance(psi.project))
        }.any { predicate(OpenIdeClass(it)) }
    }

    override fun projectPaths(project: Project): TestoPathMapping = OpenIdePathMapping(PhpLaunchEnvironment.of(project))

    override fun isTestoConfigured(project: Project): Boolean =
        CachedValuesManager.getManager(project).getCachedValue(project, CONFIGURED_KEY, {
            val configured = TestoFrameworkType.instance()
                ?.let { PhpTestFrameworks.binaryPath(project, it) } != null
            // A binary set on the Test Frameworks page changes no file, so the page's own tracker is watched too.
            CachedValueProvider.Result.create(
                configured,
                PsiModificationTracker.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                PhpTestFrameworks.settingsTracker(project),
            )
        }, false)

    // Navigation from a results-tree node stops at the method on OpenIDE; the stack trace picks up from there.
    override fun dataSetNavigatable(location: Location<*>): Navigatable? = null

    override fun configurationFactory(): ConfigurationFactory = TestoRunConfigurationType.instance()

    /**
     * Translations through the environment of every interpreter profile whose process runs off the IDE's machine, the
     * active profile first. The profile list is shared by all projects, so a translation leading out of this project —
     * into the directory of another project's Compose file, say — answers null: the core takes the first translation
     * that names an existing file, and that file would belong to someone else. A translation that leaves the path as it
     * was answers null too.
     */
    override fun coverageSourcePathMappers(project: Project): List<(String) -> String?> {
        val root = project.basePath ?: return emptyList()
        return pathMappersOf(root, PhpLaunchEnvironment.allOf(project))
    }

    companion object {
        private const val PHP_LANGUAGE = "PHP"

        private val CONFIGURED_KEY = Key.create<CachedValue<Boolean>>("testo.openide.configured")

        private val HEIRS_KEY = Key.create<CachedValue<ConcurrentMap<String, List<PsiElement>>>>("testo.openide.heirs")

        fun viewOf(element: PsiElement?): PhpElementView? {
            if (element == null || !element.isValid) return null
            classElementOf(element)?.let { return OpenIdeClass(it) }
            if (PhpSyntax.functionKindOf(element) != null) return OpenIdeFunction(element)
            if (PhpAttributes.at(element) != null) return OpenIdeAttribute(element)
            if (isWrittenName(element)) return OpenIdeClassReference(element)
            if (PhpInstantiations.at(element)?.element == element) return OpenIdeNewExpression(element)
            return null
        }

        /** The class [element] stands for: a declared or anonymous class, or the class a class body belongs to. */
        private fun classElementOf(element: PsiElement): PsiElement? = when {
            PhpSyntax.isAnonymousClass(element) -> element
            isNamedClass(element) -> element
            else -> PhpDeclarations.classOfBody(element)
        }

        private fun isNamedClass(element: PsiElement) = PhpDeclarations.classOf(element).valueOrNull() != null

        private fun isFreeFunction(element: PsiElement) = PhpDeclarations.functionOf(element).valueOrNull() != null

        /** A written name (`\Testo\Test`, `SuiteConfig`): the element whose name leaf is its last segment. */
        private fun isWrittenName(element: PsiElement): Boolean =
            element.firstChild != null
                && PhpSyntax.nameLeafOf(element) != null
                && PhpSyntax.functionKindOf(element) == null
                && !isNamedClass(element)

        fun withLeadingBackslash(fqn: String) = if (fqn.startsWith("\\")) fqn else "\\$fqn"

        /**
         * The translations of [environments] whose process runs off the IDE's machine, in their order, each answering a
         * path inside [root] or null — see [coverageSourcePathMappers].
         */
        internal fun pathMappersOf(root: String, environments: List<PhpLaunchEnvironment>): List<(String) -> String?> =
            environments
                .filter { it.locality != PhpLaunchLocality.SameHost }
                .map { environment ->
                    { path: String ->
                        val written = FileUtil.toSystemIndependentName(path)
                        FileUtil.toSystemIndependentName(environment.toHost(path))
                            .takeIf { it != written && FileUtil.isAncestor(root, it, false) }
                    }
                }
    }
}

/** Paths through the launch environment of the active interpreter profile. */
class OpenIdePathMapping(private val environment: PhpLaunchEnvironment) : TestoPathMapping {
    override fun toEnvironment(localPath: String): String = environment.toEnvironment(localPath)

    override fun toLocalFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(FileUtil.toSystemIndependentName(environment.toHost(path)))

    override fun toLocalPath(path: String): String = environment.toHost(path)
}

/** A declared class, interface, trait or enum, or an anonymous class. */
data class OpenIdeClass(override val psi: PsiElement) : PhpClassView {
    private val info: PhpClassInfo? get() = PhpDeclarations.classOf(psi).valueOrNull()

    /** An anonymous class's body, found back from it; a declared class names its own. */
    private val body: PsiElement?
        get() = info?.body ?: psi.children.firstOrNull { PhpDeclarations.classOfBody(it) == psi }

    override val name: String get() = nameIdentifier?.text.orEmpty()
    override val nameIdentifier: PsiElement? get() = PhpSyntax.nameLeafOf(psi)
    override val attributes: List<PhpAttributeView> get() = attributesOf(psi)
    override fun attributes(fqn: String): List<PhpAttributeView> = attributesOf(psi, fqn)

    override val fqn: String get() = info?.fqn?.let(OpenIdeTestoPhp::withLeadingBackslash).orEmpty()

    // As PhpStorm answers them: an interface is abstract, an enum is final.
    override val isAbstract: Boolean
        get() = info?.let { it.kind == PhpDeclarationKind.INTERFACE || it.flags.isAbstract } ?: false
    override val isFinal: Boolean
        get() = info?.let { it.kind == PhpDeclarationKind.ENUM || it.flags.isFinal } ?: false

    override val ownMethods: List<PhpFunctionView>
        get() = (info?.ownMethods ?: body?.children?.filter { PhpSyntax.functionKindOf(it) == PhpFunctionKind.METHOD })
            .orEmpty()
            .map(::OpenIdeFunction)

    override fun findOwnMethod(name: String): PhpFunctionView? = ownMethods.firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun findMethod(name: String): PhpFunctionView? =
        if (info == null) findOwnMethod(name) else PhpHierarchy.findMethod(psi, name)?.let(::OpenIdeFunction)

    override val bodyStartOffset: Int? get() = body?.textOffset
}

/**
 * A free function, a method, a closure or an arrow function. A function that is no method answers public, not static,
 * not abstract: the modifiers only mean something on a method.
 */
data class OpenIdeFunction(override val psi: PsiElement) : PhpFunctionView {
    private val kind: PhpFunctionKind? get() = PhpSyntax.functionKindOf(psi)
    private val flags get() = PhpDeclarations.methodOf(psi).valueOrNull()?.flags

    override val name: String get() = nameIdentifier?.text.orEmpty()
    override val nameIdentifier: PsiElement? get() = PhpSyntax.nameLeafOf(psi)
    override val attributes: List<PhpAttributeView> get() = attributesOf(psi)
    override fun attributes(fqn: String): List<PhpAttributeView> = attributesOf(psi, fqn)

    override val fqn: String
        get() = when (kind) {
            PhpFunctionKind.FUNCTION -> PhpDeclarations.functionOf(psi).valueOrNull()?.fqn?.let(OpenIdeTestoPhp::withLeadingBackslash).orEmpty()
            PhpFunctionKind.METHOD -> "${containingClass?.fqn.orEmpty()}.$name"
            else -> ""
        }

    override val isMethod: Boolean get() = kind == PhpFunctionKind.METHOD

    override val containingClass: PhpClassView?
        get() = if (!isMethod) null else generateSequence(psi.parent) { it.parent }
            .firstOrNull { PhpSyntax.isAnonymousClass(it) || PhpDeclarations.classOf(it).valueOrNull() != null }
            ?.let(::OpenIdeClass)

    override val isPublic: Boolean get() = (flags?.visibility ?: PhpVisibility.PUBLIC) == PhpVisibility.PUBLIC
    override val isStatic: Boolean get() = flags?.isStatic ?: false

    // An interface method is abstract, as PhpStorm answers it.
    override val isAbstract: Boolean
        get() = isMethod && (flags?.isAbstract == true || (containingClass as? OpenIdeClass)?.let {
            PhpDeclarations.classOf(it.psi).valueOrNull()?.kind == PhpDeclarationKind.INTERFACE
        } == true)
}

data class OpenIdeAttribute(override val psi: PsiElement) : PhpAttributeView {
    private val attribute: PhpAttribute? get() = PhpAttributes.at(psi)

    override val fqn: String? get() = attribute?.fqn?.let(OpenIdeTestoPhp::withLeadingBackslash)
    override val owner: PsiElement? get() = attribute?.owner
    override val nameReference: PsiElement? get() = attribute?.nameReference
    override val parameters: List<PsiElement> get() = attribute?.argumentExpressions.orEmpty()
    override fun stringContents(parameter: PsiElement): String? = stringLiteralContents(parameter)

    override fun argument(name: String?, index: Int): PhpArgumentText? =
        attribute?.argumentExpression(name, index)?.let(::argumentText)

    override val arguments: List<PhpArgumentText?> get() = parameters.map(::argumentText)
}

data class OpenIdeClassReference(override val psi: PsiElement) : PhpClassReferenceView {
    override val fqn: String get() = OpenIdeTestoPhp.withLeadingBackslash(PhpNames.resolveClassName(psi.text, psi))

    override val newExpression: PhpNewExpressionView?
        get() = PhpInstantiations.at(psi)?.takeIf { it.classReference == psi }?.let { OpenIdeNewExpression(it.element) }

    override val attribute: PhpAttributeView?
        get() = psi.parent?.let(PhpAttributes::at)?.takeIf { it.nameReference == psi }?.let { OpenIdeAttribute(it.element) }
}

data class OpenIdeNewExpression(override val psi: PsiElement) : PhpNewExpressionView {
    private val instantiation: PhpInstantiation? get() = PhpInstantiations.at(psi)

    override val classFqn: String? get() = instantiation?.classFqn?.let(OpenIdeTestoPhp::withLeadingBackslash)

    override val firstStringArgument: String?
        get() = instantiation?.argumentExpressions?.firstOrNull()?.let(::stringLiteralContents)
}

private fun attributesOf(declaration: PsiElement, fqn: String? = null): List<PhpAttributeView> =
    PhpAttributes.of(declaration).valueOrNull().orEmpty()
        .filter { fqn == null || it.fqn == fqn.removePrefix("\\") }
        .map { OpenIdeAttribute(it.element) }

/**
 * The text between the quotes of a single- or double-quoted string literal, as written — what PhpStorm's
 * `StringLiteralExpression.contents` gives; null for anything else.
 */
private fun stringLiteralContents(expression: PsiElement): String? {
    if (PhpLiterals.of(expression) !is PhpLiteral.Text) return null
    val text = expression.text
    val quote = text.firstOrNull()?.takeIf { it == '\'' || it == '"' } ?: return null
    return if (text.length >= 2 && text.last() == quote) text.substring(1, text.length - 1) else null
}

/** An argument as written; a string literal is told apart, as PhpStorm's constant arguments are. */
private fun argumentText(expression: PsiElement) = PhpArgumentText(expression.text, stringLiteralContents(expression) != null)
