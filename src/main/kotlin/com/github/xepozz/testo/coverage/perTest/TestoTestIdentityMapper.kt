package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.coverage.format.TestId
import com.github.xepozz.testo.tests.TestoTestRunLineMarkerProvider
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method

/**
 * The one place that maps a coverage [TestId] (a `\`-qualified class + method, as coverage-xml spells covering tests)
 * onto Testo's own identities — so its consumers cannot diverge. A `--filter` selector is a pure string (available
 * with no PSI); the `php_qn://` hint and PSI need the class resolved through [PhpIndex].
 */
interface TestoTestIdentityMapper {
    /** `\Ns\FooTest::method` — the selector Testo's `--filter` accepts (matches TestoRunTarget.filterOf output). */
    fun toFilterSelector(id: TestId): String

    /** The canonical `php_qn://…` location hint, or null when the class/method cannot be resolved. */
    fun toLocationHint(id: TestId, project: Project): String?

    fun resolve(id: TestId, project: Project): PsiElement?

    companion object {
        fun getInstance(): TestoTestIdentityMapper = DefaultTestIdentityMapper
    }
}

/** Opens [id]'s method. The class is looked up in the index, which the EDT may not read: off it, then back to navigate. */
fun navigateToTest(project: Project, id: TestId) {
    ReadAction.nonBlocking<Navigatable?> {
        (TestoTestIdentityMapper.getInstance().resolve(id, project) as? Navigatable)?.takeIf { it.canNavigate() }
    }
        .inSmartMode(project)
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { it?.navigate(true) }
        .submit(AppExecutorUtil.getAppExecutorService())
}

internal object DefaultTestIdentityMapper : TestoTestIdentityMapper {
    override fun toFilterSelector(id: TestId): String = "\\" + id.fqcn.trimStart('\\') + "::" + id.method

    override fun toLocationHint(id: TestId, project: Project): String? =
        (resolve(id, project) as? Method)?.let { TestoTestRunLineMarkerProvider.getLocationHint(it) }

    override fun resolve(id: TestId, project: Project): PsiElement? {
        val fqn = "\\" + id.fqcn.trimStart('\\')
        return PhpIndex.getInstance(project).getClassesByFQN(fqn)
            .firstNotNullOfOrNull { it.findMethodByName(id.method) }
    }
}
