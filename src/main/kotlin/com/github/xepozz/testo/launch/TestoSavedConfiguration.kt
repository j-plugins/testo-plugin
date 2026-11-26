package com.github.xepozz.testo.launch

import org.jdom.Element

/** How a configuration launches, beside what it runs: none of it is Testo's, but every build saves it in one place. */
data class TestoLaunchSettings(
    val binaryPath: String = "",
    val workingDirectory: String? = null,
    val environmentVariables: Map<String, String> = emptyMap(),
    val passParentEnvironment: Boolean = true,
    /** A build's own handle on its interpreter; empty runs on the default one. */
    val interpreterId: String = "",
    /** The interpreter's name, shared with the other builds only while [interpreterId] names one. */
    val interpreterName: String? = null,
    /** Arguments to php itself. */
    val interpreterOptions: String? = null,
)

/**
 * A Testo run configuration in the XML every build reads and writes: `<TestoRunnerSettings>` and `<CommandLine>` in the
 * shape the platform serializer has always given them, so one `.run.xml` serves every build. A build that edits only part
 * of the file still writes the rest back as it found it — another build's interpreter, say.
 */
class TestoSavedConfiguration(
    val selection: TestoRunSelection,
    val launch: TestoLaunchSettings,
    private val runner: Element? = null,
    private val commandLine: Element? = null,
) {
    fun with(selection: TestoRunSelection, launch: TestoLaunchSettings) =
        TestoSavedConfiguration(selection, launch, runner, commandLine)

    fun writeTo(configuration: Element) {
        writeCommandLine()?.let(configuration::addContent)
        writeRunner()?.let(configuration::addContent)
    }

    private fun writeRunner(): Element? {
        val s = selection
        val foreign = runner != null && (
            runner.attributes.any { it.name !in RUNNER_ATTRIBUTES } ||
                runner.children.any { !it.isRunnerField() } ||
                runner.getAttributeValue("scope")?.let { it !in SCOPES.values } == true ||
                savedOption(runner, "coverageEngine")?.let { coverageDriverOf(it) == null } == true
            )
        val absent = s.copy(rerunFilters = emptyList()) == ABSENT && launch.binaryPath.isEmpty() && launch.interpreterId.isEmpty()
        if (absent && !foreign) return null

        val element = Element(RUNNER)
        fun attribute(name: String, value: Any?, default: Any?) {
            if (value != null && value != default) element.setAttribute(name, value.toString())
        }
        fun option(name: String, value: Any, default: Any) {
            if (value != default) element.addContent(Element("option").setAttribute("name", name).setAttribute("value", value.toString()))
        }
        fun list(name: String, values: List<String>) {
            if (values.isEmpty()) return
            element.addContent(Element(name).apply { values.forEach { addContent(Element("option").setAttribute("value", it)) } })
        }

        attribute("configuration_file", s.configurationFilePath, null)
        attribute("directory", s.directoryPath, null)
        attribute("file", s.filePath, null)
        attribute("method", s.methodName, null)
        // A scope of a newer build reads as Directory; unless it was changed here, it is written back as it was.
        val unknownScope = runner?.getAttributeValue("scope")?.takeIf { it !in SCOPES.values && s.scope == TestoScope.DIRECTORY }
        attribute("scope", unknownScope ?: SCOPES.getValue(s.scope), SCOPES.getValue(TestoScope.DIRECTORY))
        attribute("selected_type", s.selectedType, null)
        attribute("options", s.testRunnerOptions, null)
        attribute("use_alternative_configuration_file", s.useAlternativeConfigurationFile, false)
        attribute("command", s.command, DEFAULTS.command)
        attribute("parallel", s.parallel, DEFAULTS.parallel)
        attribute("coverage_clover", s.coverageClover, DEFAULTS.coverageClover)
        attribute("coverage_cobertura", s.coverageCobertura, DEFAULTS.coverageCobertura)
        attribute("coverage_xml", s.coverageXml, DEFAULTS.coverageXml)
        attribute("coverage_level", s.coverageLevel, DEFAULTS.coverageLevel)
        attribute("coverage_options", s.coverageOptions, DEFAULTS.coverageOptions)
        attribute("log_html", s.logHtml, DEFAULTS.logHtml)
        attribute("log_junit", s.logJunit, DEFAULTS.logJunit)
        attribute("infection_scope", s.infectionScope, DEFAULTS.infectionScope)
        attribute("infection_git_diff_base", s.infectionGitDiffBase, "")
        attribute("infection_threads", s.infectionThreads, "")
        attribute("infection_only_covering_test_cases", s.infectionOnlyCoveringTestCases, false)
        attribute("infection_with_uncovered", s.infectionWithUncovered, false)
        attribute("infection_timeouts_as_escaped", s.infectionTimeoutsAsEscaped, false)
        attribute("infection_mutators", s.infectionMutators, "")
        attribute("infection_static_analysis_tool", s.infectionStaticAnalysisTool, "")
        attribute("infection_options", s.infectionOptions, "")
        attribute("binary", launch.binaryPath, "")
        attribute("interpreter_id", launch.interpreterId, "")
        runner?.attributes?.filter { it.name !in RUNNER_ATTRIBUTES }?.forEach { element.setAttribute(it.clone()) }

        // Kept like an unknown scope: an engine of a newer build reads as the default.
        val unknownEngine = runner?.let { savedOption(it, "coverageEngine") }
            ?.takeIf { coverageDriverOf(it) == null && s.coverageDriver == DEFAULTS.coverageDriver }
        option(
            "coverageEngine",
            unknownEngine ?: COVERAGE_DRIVERS.getValue(s.coverageDriver),
            COVERAGE_DRIVERS.getValue(DEFAULTS.coverageDriver),
        )
        option("dataProviderIndex", s.dataProviderIndex, DEFAULTS.dataProviderIndex)
        option("dataSetIndex", s.dataSetIndex, DEFAULTS.dataSetIndex)
        list("exclude_groups", s.excludeGroups)
        list("groups", s.groups)
        option("parallelTestingEnabled", s.parallelTestingEnabled, DEFAULTS.parallelTestingEnabled)
        list("suites", s.suites)
        list("testo_types", s.testoTypes)
        runner?.children?.filter { !it.isRunnerField() }?.forEach { element.addContent(it.clone()) }
        return element
    }

    private fun writeCommandLine(): Element? {
        val element = commandLine?.clone() ?: Element(COMMAND_LINE)
        if (launch.passParentEnvironment) element.removeAttribute("pass_parent_envs")
        else element.setAttribute("pass_parent_envs", "false")
        val workingDirectory = launch.workingDirectory
        if (workingDirectory == null) element.removeAttribute("workingDirectory")
        else element.setAttribute("workingDirectory", workingDirectory)
        val interpreterOptions = launch.interpreterOptions
        if (interpreterOptions.isNullOrEmpty()) element.removeAttribute("parameters")
        else element.setAttribute("parameters", interpreterOptions)
        // Without an interpreter chosen here, the file keeps whichever another build chose.
        val interpreterName = launch.interpreterName
        if (launch.interpreterId.isNotEmpty() && !interpreterName.isNullOrEmpty()) {
            val interpreter = element.getChild(INTERPRETER) ?: Element(INTERPRETER).also { element.addContent(0, it) }
            val option = interpreter.getChildren("option").firstOrNull { it.getAttributeValue("name") == "interpreterName" }
                ?: Element("option").setAttribute("name", "interpreterName").also(interpreter::addContent)
            option.setAttribute("value", interpreterName)
        }

        val old = element.getChild("envs")
        val at = old?.let(element::indexOf) ?: -1
        old?.let(element::removeContent)
        if (launch.environmentVariables.isNotEmpty()) {
            // The variables may come back in hash order; the order read stays, so a save without changes is no diff.
            val readOrder = old?.getChildren("env")?.mapNotNull { it.getAttributeValue("name") }.orEmpty()
            val names = readOrder.filter(launch.environmentVariables::containsKey) +
                launch.environmentVariables.keys.filter { it !in readOrder }
            val envs = Element("envs")
            names.associateWith(launch.environmentVariables::getValue).forEach { (name, value) ->
                envs.addContent(Element("env").setAttribute("name", name).setAttribute("value", value))
            }
            if (at >= 0) element.addContent(at, envs) else element.addContent(envs)
        }

        // In the serializer's order, so saves from different builds do not reorder the attributes.
        val attributes = element.attributes.map { it.clone() }
            .sortedBy { COMMAND_LINE_ATTRIBUTES.indexOf(it.name).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }
        attributes.forEach { element.removeAttribute(it.name) }
        attributes.forEach(element::setAttribute)
        return element.takeIf { it.attributes.isNotEmpty() || it.children.isNotEmpty() }
    }

    companion object {
        private const val RUNNER = "TestoRunnerSettings"
        private const val COMMAND_LINE = "CommandLine"
        private const val INTERPRETER = "PhpTestInterpreterSettings"

        /** What a configuration without `<TestoRunnerSettings>` runs: the default the serialized settings leave out. */
        const val DEFAULT_RUNNER_OPTIONS = "-q -n --teamcity"

        private val DEFAULTS = TestoRunSelection()

        /** The field values that mean "nothing saved": they leave the element out, and an absent element reads as them. */
        private val ABSENT = TestoRunSelection(scope = TestoScope.DIRECTORY, testRunnerOptions = DEFAULT_RUNNER_OPTIONS)

        private val SCOPES = mapOf(
            TestoScope.TYPE to "Type",
            TestoScope.DIRECTORY to "Directory",
            TestoScope.FILE to "File",
            TestoScope.METHOD to "Method",
            TestoScope.CONFIGURATION_FILE to "ConfigurationFile",
        )

        private val COVERAGE_DRIVERS = mapOf(
            TestoCoverageDriver.XDEBUG to "XDebug",
            TestoCoverageDriver.PCOV to "PCov",
            TestoCoverageDriver.PHPDBG to "PHPDBG",
        )

        private val RUNNER_ATTRIBUTES = setOf(
            "configuration_file", "directory", "file", "method", "scope", "selected_type", "options",
            "use_alternative_configuration_file", "command", "parallel", "coverage_clover", "coverage_cobertura",
            "coverage_xml", "coverage_level", "coverage_options", "log_html", "log_junit", "infection_scope",
            "infection_git_diff_base", "infection_threads", "infection_only_covering_test_cases",
            "infection_with_uncovered", "infection_timeouts_as_escaped", "infection_mutators",
            "infection_static_analysis_tool", "infection_options", "binary", "interpreter_id",
            "group", "exclude_group", "suite", "testo_type",
        )

        private val RUNNER_OPTIONS = setOf("coverageEngine", "dataProviderIndex", "dataSetIndex", "parallelTestingEnabled")
        private val RUNNER_LISTS = setOf("exclude_groups", "groups", "suites", "testo_types")

        private fun Element.isRunnerField() =
            name in RUNNER_LISTS || name == "option" && getAttributeValue("name") in RUNNER_OPTIONS

        private fun savedOption(runner: Element, name: String): String? =
            runner.getChildren("option").firstOrNull { it.getAttributeValue("name") == name }?.getAttributeValue("value")

        private fun coverageDriverOf(saved: String): TestoCoverageDriver? =
            COVERAGE_DRIVERS.entries.firstOrNull { it.value.equals(saved, true) || it.key.name.equals(saved, true) }?.key

        private val COMMAND_LINE_ATTRIBUTES = listOf("parameters", "pass_parent_envs", "workingDirectory")

        fun readFrom(configuration: Element): TestoSavedConfiguration {
            val runner = configuration.getChild(RUNNER)
            val commandLine = configuration.getChild(COMMAND_LINE)
            return TestoSavedConfiguration(
                selection = runner?.let(::readSelection) ?: ABSENT.copy(),
                launch = readLaunch(runner, commandLine),
                runner = runner?.clone(),
                commandLine = commandLine?.clone(),
            )
        }

        private fun readSelection(runner: Element): TestoRunSelection {
            fun string(name: String): String? = runner.getAttributeValue(name)
            fun boolean(name: String, default: Boolean) = string(name)?.toBooleanStrictOrNull() ?: default
            fun option(name: String): String? = savedOption(runner, name)
            fun list(name: String): List<String> =
                runner.getChild(name)?.getChildren("option")?.mapNotNull { it.getAttributeValue("value") }.orEmpty()
            fun names(text: String?): List<String>? =
                if (text.isNullOrEmpty()) null else text.split(',').map(String::trim).filter(String::isNotEmpty)

            return TestoRunSelection(
                scope = SCOPES.entries.firstOrNull { it.value == string("scope") }?.key ?: TestoScope.DIRECTORY,
                selectedType = string("selected_type"),
                directoryPath = string("directory"),
                filePath = string("file"),
                methodName = string("method"),
                useAlternativeConfigurationFile = boolean("use_alternative_configuration_file", false),
                configurationFilePath = string("configuration_file"),
                testRunnerOptions = string("options"),
                command = string("command") ?: DEFAULTS.command,
                testoTypes = string("testo_type")?.takeIf(String::isNotEmpty)?.let(::listOf) ?: list("testo_types"),
                suites = string("suite")?.takeIf(String::isNotEmpty)?.let(::listOf) ?: list("suites"),
                groups = names(string("group")) ?: list("groups"),
                excludeGroups = names(string("exclude_group")) ?: list("exclude_groups"),
                dataProviderIndex = option("dataProviderIndex")?.toIntOrNull() ?: DEFAULTS.dataProviderIndex,
                dataSetIndex = option("dataSetIndex")?.toIntOrNull() ?: DEFAULTS.dataSetIndex,
                coverageDriver = option("coverageEngine")?.let(::coverageDriverOf) ?: DEFAULTS.coverageDriver,
                coverageClover = boolean("coverage_clover", DEFAULTS.coverageClover),
                coverageCobertura = boolean("coverage_cobertura", DEFAULTS.coverageCobertura),
                coverageXml = boolean("coverage_xml", DEFAULTS.coverageXml),
                coverageLevel = string("coverage_level") ?: DEFAULTS.coverageLevel,
                coverageOptions = string("coverage_options") ?: DEFAULTS.coverageOptions,
                parallel = string("parallel")?.toIntOrNull() ?: DEFAULTS.parallel,
                parallelTestingEnabled = option("parallelTestingEnabled")?.toBooleanStrictOrNull() ?: DEFAULTS.parallelTestingEnabled,
                logHtml = boolean("log_html", DEFAULTS.logHtml),
                logJunit = boolean("log_junit", DEFAULTS.logJunit),
                infectionScope = string("infection_scope") ?: DEFAULTS.infectionScope,
                infectionGitDiffBase = string("infection_git_diff_base").orEmpty(),
                infectionThreads = string("infection_threads").orEmpty(),
                infectionOnlyCoveringTestCases = boolean("infection_only_covering_test_cases", false),
                infectionWithUncovered = boolean("infection_with_uncovered", false),
                infectionTimeoutsAsEscaped = boolean("infection_timeouts_as_escaped", false),
                infectionMutators = string("infection_mutators").orEmpty(),
                infectionStaticAnalysisTool = string("infection_static_analysis_tool").orEmpty(),
                infectionOptions = string("infection_options").orEmpty(),
            )
        }

        private fun readLaunch(runner: Element?, commandLine: Element?): TestoLaunchSettings {
            val interpreterId = runner?.getAttributeValue("interpreter_id").orEmpty()
            return TestoLaunchSettings(
                binaryPath = runner?.getAttributeValue("binary").orEmpty(),
                workingDirectory = commandLine?.getAttributeValue("workingDirectory"),
                environmentVariables = commandLine?.getChild("envs")?.getChildren("env")
                    ?.mapNotNull { env -> env.getAttributeValue("name")?.let { it to env.getAttributeValue("value").orEmpty() } }
                    ?.toMap(LinkedHashMap())
                    .orEmpty(),
                passParentEnvironment = commandLine?.getAttributeValue("pass_parent_envs")?.toBooleanStrictOrNull() ?: true,
                interpreterId = interpreterId,
                interpreterName = commandLine?.getChild(INTERPRETER)
                    ?.getChildren("option")?.firstOrNull { it.getAttributeValue("name") == "interpreterName" }
                    ?.getAttributeValue("value")
                    ?.takeIf { interpreterId.isNotEmpty() },
                interpreterOptions = commandLine?.getAttributeValue("parameters"),
            )
        }
    }
}
