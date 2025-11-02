package com.github.xepozz.testo.tests.overrides

import com.intellij.ui.ColoredListCellRenderer
import com.jetbrains.php.PhpBundle
import com.jetbrains.php.lang.findUsages.PhpGotoTargetRendererProvider
import javax.swing.JList

class PhpRunInheritorsListCellRenderer(
    private val myNumberOfInheritors: Int,
    showMethodNames: Boolean
) : PhpGotoTargetRendererProvider.PhpNamedElementPsiElementListCellRenderer(showMethodNames) {
    override fun customizeNonPsiElementLeftRenderer(
        renderer: ColoredListCellRenderer<*>,
        list: JList<*>,
        value: Any?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) = when (value) {
        null -> renderer.append(PhpBundle.message("all.0", this.myNumberOfInheritors)).let { true }

        else -> super.customizeNonPsiElementLeftRenderer(renderer, list, value, index, selected, hasFocus)
    }
}