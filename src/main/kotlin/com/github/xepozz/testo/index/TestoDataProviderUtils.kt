package com.github.xepozz.testo.index

import com.github.xepozz.testo.isTestoDataProvider
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.indexing.FileBasedIndex
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
}