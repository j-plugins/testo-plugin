package com.github.xepozz.testo.index

import com.github.xepozz.testo.isTestoDataProviderLike
import com.github.xepozz.testo.isTestoFunction
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.rd.util.printlnError

object TestoDataProviderUtils {
    fun isDataProvider(function: Function): Boolean {
        if (!function.isTestoDataProviderLike()) return false

        return FileBasedIndex.getInstance()
            .getValues(
                TestoDataProvidersIndex.KEY,
                function.name,
                GlobalSearchScopesCore.projectTestScope(function.project)
            )
            .isNotEmpty()
    }

    fun findDataProviderUsages(function: Function): List<Method> {
        if (!function.isTestoDataProviderLike()) return emptyList()
        val phpIndex = PhpIndex.getInstance(function.project)

        return FileBasedIndex.getInstance()
            .getValues(
                TestoDataProvidersIndex.KEY,
                function.name,
                GlobalSearchScopesCore.projectTestScope(function.project)
            )
            .flatMap { it }
            .flatMap { usage ->
                phpIndex
                    .getClassesByFQN(usage.classFqn)
                    .mapNotNull { it.findOwnMethodByName(usage.methodName) }
            }
    }

    fun findDataProviderUsagesIndex(test: Function, dataProvider: Function): Int {
        if (!test.isTestoFunction()) return -1

        val mapping = TestoDataProvidersIndex.getDataProvidersFromAttributes(test)

        val indexByFqn = mapping.indexOfFirst { it.first == dataProvider.fqn }
        if (indexByFqn != -1) return indexByFqn

        val indexByName = mapping.indexOfFirst { it.second == dataProvider.name }
        if (indexByName != -1) return indexByName

        printlnError("Could not find data provider usage for ${dataProvider.name} (${dataProvider.fqn}) in ${test.name} (${test.fqn})")
        return -1
    }
}