package com.github.xepozz.testo.openide.tests

import com.github.xepozz.testo.tests.TestoLocationHints
import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import ru.openide.openphp.run.testing.PhpTestLocator
import ru.openide.openphp.run.testing.PhpTestPathTranslator

/**
 * Finds a results-tree node's source through PHP for OpenIDE's locator, which reads the same `php_qn` addresses Testo
 * reports by. Testo's coordinates (`med:3:0`, `#1`, ` with data set #N`) name no PHP member, so they come off first —
 * navigation stops at the method, as on PhpStorm.
 */
class TestoTestLocator(translator: PhpTestPathTranslator) : SMTestLocator {
    private val locator = PhpTestLocator(translator)

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        val parsed = TestoLocationHints.parse(path) ?: return emptyList()
        val address = listOfNotNull(parsed.filePath, parsed.className, parsed.methodName).joinToString("::")
        return locator.getLocation(protocol, address, project, scope)
    }
}
