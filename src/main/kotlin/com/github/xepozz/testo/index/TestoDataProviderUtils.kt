package com.github.xepozz.testo.index

import com.github.xepozz.testo.isTestoDataProvider
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method

object TestoDataProviderUtils {
    fun isDataProvider(method: Method): Boolean {
        if (!method.isTestoDataProvider()) return false

        return FileBasedIndex.getInstance()
            .getValues(
                TestoDataProvidersIndex.KEY,
                method.name,
                GlobalSearchScopesCore.projectTestScope(method.project)
            )
            .isNotEmpty()
    }

    fun findDataProviderUsages(method: Function): List<Method> {
        if (!method.isTestoDataProvider()) return emptyList()
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