package com.github.xepozz.testo.baseline

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/**
 * Sets Testo up in a test project the way an OpenIDE user has it: `testo/testo` in the project's composer.json, from
 * which PHP for OpenIDE finds the executable. Each PHP implementation's tests provide this object under the same name,
 * so the core tests set Testo up without knowing which implementation they run on.
 */
object TestoProjectSetUp {
    fun setUp(fixture: CodeInsightTestFixture) {
        fixture.addFileToProject("composer.json", """{"require-dev": {"testo/testo": "^0.10"}}""")
    }

    // The composer.json goes with the rest of the test project.
    fun tearDown(fixture: CodeInsightTestFixture) {}
}
