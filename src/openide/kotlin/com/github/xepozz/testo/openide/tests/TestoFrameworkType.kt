package com.github.xepozz.testo.openide.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.github.xepozz.testo.launch.TestoCommandLine
import com.github.xepozz.testo.tests.run.TestoRunPaths
import ru.openide.openphp.lang.psi.elements.PhpClassDeclaration
import ru.openide.openphp.lang.psi.elements.PhpMethodDeclaration
import ru.openide.openphp.run.testing.PhpTestArguments
import ru.openide.openphp.run.testing.PhpTestCreateInfo
import ru.openide.openphp.run.testing.PhpTestDescriptor
import ru.openide.openphp.run.testing.PhpTestFrameworkType
import ru.openide.openphp.run.testing.PhpTestFrameworkVersionProbe
import ru.openide.openphp.run.testing.PhpTestFrameworks
import ru.openide.openphp.run.testing.PhpTestLaunch
import ru.openide.openphp.run.testing.PhpTestLaunchRules
import javax.swing.Icon

/**
 * Testo on the test framework page of PHP for OpenIDE: the binary found through composer, the version probe and the
 * "New Test" entry. Testo runs through its own configuration, so the page is all it registers for: the descriptor
 * claims no class, which keeps the platform's test icon and producer off Testo classes, and the launch rules are never
 * asked.
 */
class TestoFrameworkType : PhpTestFrameworkType {
    override val id: String = ID
    override val displayName: String = TestoBundle.message("testo.local.run.display.name")
    override val icon: Icon get() = TestoIcons.TESTO
    override val composerPackages: List<String> = listOf("testo/testo")
    override val binaryName: String = "testo"
    override val binaryRelativePath: String = "bin/testo"
    override val configFileNames: List<String> = listOf(TestoRunPaths.DEFAULT_CONFIG_NAME)

    override val versionProbe: PhpTestFrameworkVersionProbe = object : PhpTestFrameworkVersionProbe {
        override val arguments: List<String> = listOf("--version", "--no-ansi")

        override fun parse(output: String): String? = output.substringAfter("Testo ", "").trim().ifEmpty { null }
    }

    override val descriptor: PhpTestDescriptor = object : PhpTestDescriptor {
        override fun isTestClass(classDeclaration: PhpClassDeclaration): Boolean = false

        override fun isTestMethod(method: PhpMethodDeclaration): Boolean = false
    }

    override val launchRules: PhpTestLaunchRules = object : PhpTestLaunchRules {
        override val machineOutput: List<String> = emptyList()
        override val configFileOption: String = TestoCommandLine.CONFIG_OPTION

        override fun coverageReport(reportPath: String): List<String>? = null

        override fun arguments(launch: PhpTestLaunch): PhpTestArguments =
            PhpTestArguments.Refuse("Testo runs through its own run configuration.")
    }

    override val newTest: PhpTestCreateInfo
        get() = PhpTestCreateInfo("Testo Test", TestoIcons.TESTO, TEMPLATE)

    companion object {
        const val ID = "Testo"

        /** The template the core registers (`internalFileTemplate`), shared with PhpStorm's "New Test". */
        const val TEMPLATE = "Testo Test"

        fun instance(): TestoFrameworkType? = PhpTestFrameworks.byId(ID) as? TestoFrameworkType
    }
}
