package com.github.xepozz.testo.coverage.perTest

import com.github.xepozz.testo.coverage.format.PerTestCoverage
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Holds the latest per-test coverage for the project so consumers can read it with no active coverage session.
 * Populated by [com.github.xepozz.testo.coverage.TestoCoverageRunner] when a `coverage-xml` report is loaded.
 *
 * In-memory: survives until the IDE closes or the next coverage-xml run replaces it.
 */
@Service(Service.Level.PROJECT)
class TestoCoverageByTestIndex {
    @Volatile
    private var data: TestoCoverageByTestData = TestoCoverageByTestData.EMPTY

    /** Only a report that actually carries per-test data replaces the index — a plain clover/cobertura run leaves it. */
    fun update(perTest: PerTestCoverage?) {
        if (perTest != null) data = TestoCoverageByTestData.of(perTest)
    }

    fun data(): TestoCoverageByTestData = data

    companion object {
        fun getInstance(project: Project): TestoCoverageByTestIndex = project.service()
    }
}
