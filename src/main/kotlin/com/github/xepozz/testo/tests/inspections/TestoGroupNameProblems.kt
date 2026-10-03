package com.github.xepozz.testo.tests.inspections

import com.github.xepozz.testo.TestoBundle

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
