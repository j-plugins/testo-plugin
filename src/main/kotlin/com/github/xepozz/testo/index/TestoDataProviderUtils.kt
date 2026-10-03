package com.github.xepozz.testo.index

import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoFunction
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.indexing.FileBasedIndex

object TestoDataProviderUtils {
    private val php: TestoPhp get() = TestoPhp.getInstance()

    fun isDataProvider(function: PsiElement): Boolean {
        if (!function.isTestoDataProviderLike()) return false
        val name = (php.view(function) as? PhpFunctionView)?.name ?: return false

        return FileBasedIndex.getInstance()
            .getValues(
                TestoDataProvidersIndex.KEY,
                name,
                GlobalSearchScopesCore.projectTestScope(function.project)
            )
            .isNotEmpty()
    }

    /** The test methods that name [function] as their data provider, ordered by file and source position. */
    fun findDataProviderUsages(function: PsiElement): List<PsiElement> {
        if (!function.isTestoDataProviderLike()) return emptyList()
        val name = (php.view(function) as? PhpFunctionView)?.name ?: return emptyList()
        val project = function.project

        return FileBasedIndex.getInstance()
            .getValues(
                TestoDataProvidersIndex.KEY,
                name,
                GlobalSearchScopesCore.projectTestScope(project)
            )
            .flatMap { it }
            .flatMap { usage ->
                php.classesByFqn(project, usage.classFqn)
                    .mapNotNull { it.findOwnMethod(usage.methodName)?.psi }
            }
            .let(::inSourceOrder)
    }

    // Index iteration order is not a stable default for a run or for the shared-provider chooser.
    internal fun inSourceOrder(usages: List<PsiElement>): List<PsiElement> =
        usages.sortedWith(compareBy({ it.containingFile.virtualFile.path }, { it.textOffset }))

    fun findDataProviderUsagesIndex(test: PsiElement, dataProvider: PsiElement): Int {
        if (!test.isTestoFunction()) return -1
        val provider = php.view(dataProvider) as? PhpFunctionView ?: return -1

        val mapping = TestoDataProvidersIndex.getDataProvidersFromAttributes(test)

        val indexByFqn = mapping.indexOfFirst { it.first == provider.fqn }
        if (indexByFqn != -1) return indexByFqn

        val indexByName = mapping.indexOfFirst { it.second == provider.name }
        if (indexByName != -1) return indexByName

        val testView = php.view(test) as? PhpFunctionView
        thisLogger().debug("Could not find data provider usage for ${provider.name} (${provider.fqn}) in ${testView?.name} (${testView?.fqn})")
        return -1
    }
}
