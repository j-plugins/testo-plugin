package com.github.xepozz.testo

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon

// https://intellij-icons.jetbrains.design
// https://plugins.jetbrains.com/docs/intellij/icons.html#new-ui-tool-window-icons
// https://plugins.jetbrains.com/docs/intellij/icons-style.html
object TestoIcons {
    @JvmField
    val TESTO = IconLoader.getIcon("/icons/testo/icon.svg", this::class.java)

    object PHP {
        @JvmField
        val FILE = IconLoader.getIcon("/icons/php/file.svg", this::class.java)

        @JvmField
        val CLASS = IconLoader.getIcon("/icons/php/class.svg", this::class.java)

        @JvmField
        val CLASS_ABSTRACT = IconLoader.getIcon("/icons/php/classAbstract.svg", this::class.java)

        @JvmField
        val FUNCTION = IconLoader.getIcon("/icons/php/function.svg", this::class.java)
    }

    object Layered {
        @JvmField
        val FILE = LayeredIcon.layeredIcon {
            arrayOf(
                PHP.FILE,
                AllIcons.Nodes.JunitTestMark,
            )
        }

        @JvmField
        val FUNCTION = LayeredIcon.layeredIcon {
            arrayOf(
                PHP.FUNCTION,
                AllIcons.Nodes.JunitTestMark,
            )
        }

        object Class {
            @JvmField
            val CLASS = LayeredIcon.layeredIcon {
                arrayOf(
                    PHP.CLASS,
                    AllIcons.Nodes.JunitTestMark,
                )
            }

            @JvmField
            val CLASS_FINAL = LayeredIcon.layeredIcon {
                arrayOf(
                    PHP.CLASS,
                    AllIcons.Nodes.FinalMark,
                    AllIcons.Nodes.JunitTestMark,
                )
            }

            @JvmField
            val CLASS_ABSTRACT = LayeredIcon.layeredIcon {
                arrayOf(
                    PHP.CLASS_ABSTRACT,
                    AllIcons.Nodes.JunitTestMark,
                )
            }
        }
    }

}