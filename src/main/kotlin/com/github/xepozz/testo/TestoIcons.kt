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

    // One icon per Testo\Core\Value\Status case, plus the pair the run is judged by. Five shapes cover the eight:
    // check, exclamation and crossed-out circle each serve two and are told apart by colour, baked into the SVGs
    // rather than tinted at runtime.
    object Status {
        /** Verdict of the whole run — the same check/cross drawn full-bleed, so it outweighs the per-test glyphs. */
        @JvmField
        val SUCCESS = IconLoader.getIcon("/icons/status/success.svg", this::class.java)

        @JvmField
        val FAILURE = IconLoader.getIcon("/icons/status/failure.svg", this::class.java)

        // Grey pair for a run stopped before it could finish: the same verdict, in a colour that does not claim one.
        @JvmField
        val SUCCESS_CANCELLED = IconLoader.getIcon("/icons/status/successCancelled.svg", this::class.java)

        @JvmField
        val FAILURE_CANCELLED = IconLoader.getIcon("/icons/status/failureCancelled.svg", this::class.java)

        @JvmField
        val PASSED = IconLoader.getIcon("/icons/status/passed.svg", this::class.java)

        @JvmField
        val FLAKY = IconLoader.getIcon("/icons/status/flaky.svg", this::class.java)

        @JvmField
        val FAILED = IconLoader.getIcon("/icons/status/failed.svg", this::class.java)

        @JvmField
        val ERROR = IconLoader.getIcon("/icons/status/error.svg", this::class.java)

        @JvmField
        val RISKY = IconLoader.getIcon("/icons/status/risky.svg", this::class.java)

        @JvmField
        val SKIPPED = IconLoader.getIcon("/icons/status/skipped.svg", this::class.java)

        @JvmField
        val CANCELLED = IconLoader.getIcon("/icons/status/cancelled.svg", this::class.java)

        @JvmField
        val ABORTED = IconLoader.getIcon("/icons/status/aborted.svg", this::class.java)
    }

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