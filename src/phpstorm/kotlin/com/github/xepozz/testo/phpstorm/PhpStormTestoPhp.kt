package com.github.xepozz.testo.phpstorm

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
import com.intellij.openapi.editor.Document
import com.github.xepozz.testo.phpstorm.tests.TestoFrameworkType
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfiguration
import com.github.xepozz.testo.phpstorm.tests.run.TestoRunConfigurationType
import com.intellij.execution.Location
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.CommonProcessors
import com.jetbrains.php.PhpClassHierarchyUtils
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.commandLine.PhpCommandLinePathProcessor
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.GroupStatement
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.PhpYield
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.stubs.indexes.expectedArguments.PhpExpectedFunctionArgument
import com.jetbrains.php.lang.psi.stubs.indexes.expectedArguments.PhpExpectedFunctionScalarArgument
import com.jetbrains.php.phpunit.PhpPsiLocationWithDataSet
import com.jetbrains.php.run.remote.PhpRemoteInterpreterManager
import com.jetbrains.php.testFramework.PhpTestFrameworkSettingsManager
import com.jetbrains.php.util.pathmapper.PhpPathMapper
import java.util.concurrent.ExecutionException

/** [TestoPhp] on PhpStorm's PHP plugin. */
class PhpStormTestoPhp : TestoPhp {
    override fun toolEnvironment(configuration: TestoConfiguration): TestoToolEnvironment =
        (configuration.clone() as TestoRunConfiguration).captureToolEnvironment()

    override fun statementAt(leaf: PsiElement?, document: Document): TestoStatementTarget? {
        var element = leaf
        while (element != null && element !is PhpFile) {
            val parent = element.parent
            val offset = element.textRange.startOffset
            val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
            if ((parent is GroupStatement || parent is PhpClass || parent is PhpFile) &&
                document.charsSequence.subSequence(lineStart, offset).isBlank()) {
                return TestoStatementTarget(element.textRange, element)
            }
            element = parent
        }
        return null
    }

    override fun isPhpFile(file: PsiFile?): Boolean = file is PhpFile

    override fun leafKind(leaf: PsiElement): PhpLeafKind? = when (leaf.elementType) {
        PhpTokenTypes.IDENTIFIER -> PhpLeafKind.IDENTIFIER
        PhpTokenTypes.kwYIELD -> PhpLeafKind.YIELD
        PhpTokenTypes.kwRETURN -> PhpLeafKind.RETURN
        else -> null
    }

    override fun view(element: PsiElement?): PhpElementView? = viewOf(element)

    override fun topLevelClasses(file: PsiFile): List<PhpClassView> =
        (file as? PhpFile)?.topLevelDefs?.values()?.filterIsInstance<PhpClass>()?.map(::PhpStormClass).orEmpty()

    override fun topLevelFunctions(file: PsiFile): List<PhpFunctionView> =
        (file as? PhpFile)?.topLevelDefs?.values()?.filterIsInstance<Function>()?.map(::PhpStormFunction).orEmpty()

    override fun allClasses(file: PsiFile): List<PhpClassView> =
        PhpPsiUtil.findAllClasses(file).map(::PhpStormClass)

    override fun attributesIn(file: PsiFile): List<PhpAttributeView> =
        PsiTreeUtil.findChildrenOfType(file, PhpAttribute::class.java).map(::PhpStormAttribute)

    override fun classReferencesIn(file: PsiFile): List<PhpClassReferenceView> =
        PsiTreeUtil.findChildrenOfType(file, ClassReference::class.java).map(::PhpStormClassReference)

    override fun isExitStatement(element: PsiElement): Boolean = element is PhpYield || element is PhpReturn

    override fun isYield(element: PsiElement): Boolean = element is PhpYield

    override fun precedingExitKeywords(leaf: PsiElement): Int {
        var count = 0
        var current = PhpPsiUtil.findPrevSiblingOfAnyType(leaf, PhpTokenTypes.kwYIELD, PhpTokenTypes.kwRETURN)
        while (current != null) {
            count++
            current = PhpPsiUtil.findPrevSiblingOfAnyType(current, PhpTokenTypes.kwYIELD, PhpTokenTypes.kwRETURN)
        }
        return count
    }

    override fun callThrows(call: PsiElement?, exceptionFqn: String): Boolean {
        val reference = call as? MethodReference ?: return false
        return reference.multiResolve(false)
            .mapNotNull { it.element as? Function }
            .any { function ->
                function.docComment?.exceptionClasses?.any { type -> type.types.any { it == exceptionFqn } } ?: false
            }
    }

    override fun classesByFqn(project: Project, fqn: String): Collection<PhpClassView> =
        PhpIndex.getInstance(project).getClassesByFQN(fqn).map(::PhpStormClass)

    override fun allSubclasses(project: Project, fqn: String): Collection<PhpClassView> =
        PhpIndex.getInstance(project).getAllSubclasses(fqn).map(::PhpStormClass)

    override fun anySuperClass(cls: PhpClassView, predicate: (PhpClassView) -> Boolean): Boolean {
        val find = object : CommonProcessors.FindProcessor<PhpClass>() {
            override fun accept(superClass: PhpClass) = predicate(PhpStormClass(superClass))
        }
        PhpClassHierarchyUtils.processSuperClasses(cls.psi as PhpClass, false, false, find)
        return find.isFound
    }

    override fun projectPaths(project: Project): TestoPathMapping {
        val processor = projectPathProcessor(project)
        return PhpStormPathMapping(processor) { processor.createPathMapper(project) }
    }

    override fun isTestoConfigured(project: Project): Boolean =
        PhpTestFrameworkSettingsManager
            .getInstance(project)
            .getConfigurations(TestoFrameworkType.INSTANCE)
            .firstOrNull()
            ?.let { configuration -> !configuration.isLocal || StringUtil.isNotEmpty(configuration.executablePath) }
            ?: false

    override fun dataSetNavigatable(location: Location<*>): Navigatable? =
        if (location is PhpPsiLocationWithDataSet<*> && location.getPsiElement() !is Method) location.navigatable else null

    override fun configurationFactory(): ConfigurationFactory = TestoRunConfigurationType.INSTANCE

    override fun coverageSourcePathMappers(project: Project): List<(String) -> String?> {
        val configured = RunManager.getInstance(project)
            .getConfigurationsList(TestoRunConfigurationType.INSTANCE)
            .mapNotNull { (it as? TestoRunConfiguration)?.interpreter }
        val default = runCatching { PhpProjectConfigurationFacade.getInstance(project).interpreter }.getOrNull()
        return (configured + listOfNotNull(default))
            .filter(PhpInterpreter::isRemote)
            .distinctBy { it.name }
            .map { interpreter -> PhpToolLauncher(project, interpreter)::toLocal }
    }

    companion object {
        fun viewOf(element: PsiElement?): PhpElementView? = when (element) {
            is PhpClass -> PhpStormClass(element)
            is Function -> PhpStormFunction(element)
            is PhpAttribute -> PhpStormAttribute(element)
            is ClassReference -> PhpStormClassReference(element)
            is NewExpression -> PhpStormNewExpression(element)
            else -> null
        }

        /** The default interpreter's path processor; local, or local when a remote one cannot be built. */
        fun projectPathProcessor(project: Project): PhpCommandLinePathProcessor {
            val interpreter = PhpProjectConfigurationFacade.getInstance(project).interpreter
            if (interpreter?.isRemote != true) return PhpCommandLinePathProcessor.LOCAL

            val data = interpreter.phpSdkAdditionalData
            val manager = PhpRemoteInterpreterManager.getInstance() ?: return PhpCommandLinePathProcessor.LOCAL

            return try {
                manager.createPathMapper(project, data)
            } catch (_: ExecutionException) {
                PhpCommandLinePathProcessor.LOCAL
            }
        }
    }
}

/** Paths through PhpStorm's own pair: the path processor towards the interpreter, the path mapper back from it. */
class PhpStormPathMapping(
    private val processor: PhpCommandLinePathProcessor,
    mapper: () -> PhpPathMapper,
) : TestoPathMapping {
    private val mapper: PhpPathMapper by lazy(mapper)

    override fun toEnvironment(localPath: String): String =
        if (processor.canProcess(localPath)) processor.process(localPath) else localPath

    override fun toLocalFile(path: String): VirtualFile? = mapper.getLocalFile(path)

    override fun toLocalPath(path: String): String? = mapper.getLocalPath(path)
}

data class PhpStormClass(override val psi: PhpClass) : PhpClassView {
    override val name: String get() = psi.name
    override val nameIdentifier: PsiElement? get() = psi.nameIdentifier
    override val attributes: List<PhpAttributeView> get() = psi.attributes.map(::PhpStormAttribute)
    override fun attributes(fqn: String): List<PhpAttributeView> = psi.getAttributes(fqn).map(::PhpStormAttribute)
    override val fqn: String get() = psi.fqn
    override val isAbstract: Boolean get() = psi.isAbstract
    override val isFinal: Boolean get() = psi.isFinal
    override val ownMethods: List<PhpFunctionView> get() = psi.ownMethods.map(::PhpStormFunction)
    override fun findOwnMethod(name: String): PhpFunctionView? = psi.findOwnMethodByName(name)?.let(::PhpStormFunction)
    override fun findMethod(name: String): PhpFunctionView? = psi.findMethodByName(name)?.let(::PhpStormFunction)
    override val bodyStartOffset: Int? get() = PhpPsiUtil.getChildOfType(psi, PhpTokenTypes.chLBRACE)?.textOffset
}

/** A free function answers public, not static, not abstract: the modifiers only mean something on a method. */
data class PhpStormFunction(override val psi: Function) : PhpFunctionView {
    private val method: Method? get() = psi as? Method
    override val name: String get() = psi.name
    override val nameIdentifier: PsiElement? get() = psi.nameIdentifier
    override val attributes: List<PhpAttributeView> get() = psi.attributes.map(::PhpStormAttribute)
    override fun attributes(fqn: String): List<PhpAttributeView> = psi.getAttributes(fqn).map(::PhpStormAttribute)
    override val fqn: String get() = psi.fqn
    override val isMethod: Boolean get() = psi is Method
    override val containingClass: PhpClassView? get() = method?.containingClass?.let(::PhpStormClass)
    override val isPublic: Boolean get() = method?.modifier?.isPublic ?: true
    override val isStatic: Boolean get() = method?.modifier?.isStatic ?: false
    override val isAbstract: Boolean get() = method?.modifier?.isAbstract ?: false
}

data class PhpStormAttribute(override val psi: PhpAttribute) : PhpAttributeView {
    override val fqn: String? get() = psi.fqn
    override val owner: PsiElement? get() = psi.owner
    override val nameReference: PsiElement? get() = psi.classReference
    override val parameters: List<PsiElement> get() = psi.parameters.toList()
    override fun stringContents(parameter: PsiElement): String? = (parameter as? StringLiteralExpression)?.contents

    override fun argument(name: String?, index: Int): PhpArgumentText? {
        val arguments = psi.arguments
        val argument = arguments.firstOrNull { it.name == name } ?: arguments.getOrNull(index)
        return argument?.argument?.let(::textOf)
    }

    override val arguments: List<PhpArgumentText?> get() = psi.arguments.map { textOf(it.argument) }

    private fun textOf(argument: PhpExpectedFunctionArgument?): PhpArgumentText? =
        (argument as? PhpExpectedFunctionScalarArgument)?.let { PhpArgumentText(it.value, it.isStringLiteral) }
}

data class PhpStormClassReference(override val psi: ClassReference) : PhpClassReferenceView {
    override val fqn: String? get() = psi.fqn
    override val newExpression: PhpNewExpressionView? get() = (psi.parent as? NewExpression)?.let(::PhpStormNewExpression)
    override val attribute: PhpAttributeView? get() = (psi.parent as? PhpAttribute)?.let(::PhpStormAttribute)
}

data class PhpStormNewExpression(override val psi: NewExpression) : PhpNewExpressionView {
    override val classFqn: String? get() = psi.classReference?.fqn
    override val firstStringArgument: String? get() = (psi.parameters.firstOrNull() as? StringLiteralExpression)?.contents
}
