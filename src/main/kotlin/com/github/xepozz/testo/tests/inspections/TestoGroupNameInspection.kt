package com.github.xepozz.testo.tests.inspections

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoClasses
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.lang.inspections.PhpInspection
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor

/**
 * Flags `#[Group]` names the toolchain cannot select cleanly: blank or whitespace-padded names, names the CLI
 * would read as an exclusion (`!` prefix), names clashing with the comma-separated Group field of the run
 * configuration, and attributes with no names at all.
 */
class TestoGroupNameInspection : PhpInspection() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PhpElementVisitor() {
            override fun visitPhpAttribute(attribute: PhpAttribute) {
                if (attribute.fqn != TestoClasses.FILTER_GROUP) return

                val parameters = attribute.parameters
                if (parameters.isEmpty()) {
                    holder.registerProblem(
                        attribute.classReference ?: attribute,
                        TestoBundle.message("inspection.group.without.names"),
                    )
                    return
                }

                for (parameter in parameters) {
                    // Constants, concatenations etc. cannot be judged statically — stay silent on them.
                    val literal = parameter as? StringLiteralExpression ?: continue
                    val problemKey = groupNameProblemKey(literal.contents) ?: continue
                    holder.registerProblem(literal, TestoBundle.message(problemKey))
                }
            }
        }
}

/**
 * The [TestoBundle] key describing what is wrong with [name] as a group name, or null for a clean one.
 * Top-level so it is testable without the platform fixture.
 */
fun groupNameProblemKey(name: String): String? = when {
    name.isBlank() -> "inspection.group.name.blank"
    name.trim() != name -> "inspection.group.name.whitespace"
    name.startsWith("!") -> "inspection.group.name.exclusion"
    name.contains(',') -> "inspection.group.name.comma"
    else -> null
}
