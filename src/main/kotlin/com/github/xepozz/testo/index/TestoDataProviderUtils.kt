package com.github.xepozz.testo.index

import com.github.xepozz.testo.isTestoDataProviderLike
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method

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

    fun findDataProviderUsages(method: Function): List<Method> {
        if (!method.isTestoDataProviderLike()) return emptyList()
        val phpIndex = PhpIndex.getInstance(method.project)

        return FileBasedIndex.getInstance()
            .getValues(
                TestoDataProvidersIndex.KEY,
                method.name,
                GlobalSearchScopesCore.projectTestScope(method.project)
            )
            .flatMap { it }
            .flatMap { usage ->
                phpIndex
                    .getClassesByFQN(usage.classFqn)
                    .mapNotNull { it.findOwnMethodByName(usage.methodName) }
            }
    }
}