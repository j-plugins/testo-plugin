package com.github.xepozz.testo.coverage

import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.RemappingCoverageAnnotator
import com.intellij.openapi.project.Project

/**
 * File-path-keyed coverage annotation with remote-interpreter path remapping, mirroring PHP's `PhpCoverageAnnotator`.
 * [RemappingCoverageAnnotator] does the whole job; we only suppress the "0%" line string so a file with no covered
 * lines shows nothing instead of a misleading zero.
 */
class TestoCoverageAnnotator(project: Project) : RemappingCoverageAnnotator(project) {
    override fun getLinesCoverageInformationString(info: BaseCoverageAnnotator.FileCoverageInfo): String? =
        if (info.totalLineCount != 0 && info.coveredLineCount != 0) super.getLinesCoverageInformationString(info) else null

    companion object {
        fun getInstance(project: Project): TestoCoverageAnnotator = project.getService(TestoCoverageAnnotator::class.java)
    }
}
