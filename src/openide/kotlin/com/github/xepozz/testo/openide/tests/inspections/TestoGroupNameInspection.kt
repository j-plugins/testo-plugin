package com.github.xepozz.testo.openide.tests.inspections

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.openide.OpenIdeAttribute
import com.github.xepozz.testo.tests.inspections.groupNameProblemKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import ru.openide.openphp.lang.psi.symbols.PhpAttributes

/**
 * Flags `#[Group]` names the toolchain cannot select cleanly — by the core's rule, and under PhpStorm's short name, so
 * saved inspection profiles and suppressions mean the same on both IDEs.
 */
class TestoGroupNameInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val attribute = OpenIdeAttribute(PhpAttributes.at(element)?.element ?: return)
            if (attribute.fqn != TestoClasses.FILTER_GROUP) return

            val parameters = attribute.parameters
            if (parameters.isEmpty()) {
                holder.registerProblem(attribute.nameReference ?: element, TestoBundle.message("inspection.group.without.names"))
                return
            }
            for (parameter in parameters) {
                // Constants, concatenations and the like cannot be judged statically — nothing is said about them.
                val name = attribute.stringContents(parameter) ?: continue
                val problemKey = groupNameProblemKey(name) ?: continue
                holder.registerProblem(parameter, TestoBundle.message(problemKey))
            }
        }
    }
}
