package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.coverage.format.LineTotals
import com.intellij.coverage.BaseCoverageAnnotator
import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.coverage.RemappingCoverageAnnotator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData

/**
 * Answers the tool window and project view straight from the suite's [ProjectData] instead of from
 * `SimpleCoverageAnnotator`'s cache.
 *
 * That cache is filled by a content-root walk which only descends into files the engine claims through
 * `CoverageEngine.coverageProjectViewStatisticsApplicableTo` — `@ApiStatus.Internal`, defaulting to `false`, so a
 * plugin staying on public API can never fill it and every lookup returns null.
 *
 * The view reads the annotator through two overload families that feed different parts of the UI:
 * `DirectoryCoverageViewExtension.getChildrenNodes` calls the `PsiFile`/`PsiDirectory` ones (which rows exist) and
 * `getPercentage` the `VirtualFile` ones (the statistics column), so both must be answered. The `PsiFile` one is
 * overridden only to skip the interface default's canonicalization: the keys are the paths the runner resolved
 * through the VFS, and a canonical path can differ from those.
 */
class TestoCoverageAnnotator(project: Project) : RemappingCoverageAnnotator(project) {
    private val lock = Any()
    private var indexedData: ProjectData? = null
    private var index = Index(emptyMap(), emptyMap(), emptyMap())

    private class Index(
        val files: Map<String, BaseCoverageAnnotator.FileCoverageInfo>,
        val dirs: Map<String, BaseCoverageAnnotator.DirCoverageInfo>,
        // Files and directories share one map: a path is one or the other, and the platform has no branch-aware info type.
        val branches: Map<String, BranchStat>,
    )

    private class BranchStat {
        var totalBranchCount: Int = 0
        var coveredBranchCount: Int = 0
    }

    override fun onSuiteChosen(newSuite: CoverageSuitesBundle?) {
        super.onSuiteChosen(newSuite)
        synchronized(lock) {
            indexedData = null
            index = Index(emptyMap(), emptyMap(), emptyMap())
        }
    }

    override fun getFileCoverageInformationString(
        psiFile: PsiFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? {
        val file = psiFile.virtualFile ?: return null
        return getFileCoverageInformationString(psiFile.project, file, currentSuite, manager)
    }

    override fun getFileCoverageInformationString(
        project: Project,
        file: VirtualFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? {
        val files = indexFor(currentSuite)?.files ?: return null
        val info = files[key(file.path)] ?: files[key(file.canonicalPath ?: return null)] ?: return null
        return getLinesCoverageInformationString(info)
    }

    override fun getDirCoverageInformationString(
        project: Project,
        directory: VirtualFile,
        currentSuite: CoverageSuitesBundle,
        manager: CoverageDataManager,
    ): String? {
        val dirs = indexFor(currentSuite)?.dirs ?: return null
        val info = dirs[key(directory.path)] ?: dirs[key(directory.canonicalPath ?: return null)] ?: return null
        val filesInfo = getFilesCoverageInformationString(info) ?: return null
        val linesInfo = getLinesCoverageInformationString(info) ?: return filesInfo
        return "$filesInfo, $linesInfo"
    }

    // A file the report lists without executable lines would otherwise read 100% — calcPercent answers a zero total that way.
    override fun getLinesCoverageInformationString(info: BaseCoverageAnnotator.FileCoverageInfo): String? =
        if (info.totalLineCount == 0) null else super.getLinesCoverageInformationString(info)

    /** Branch coverage of a file or of everything under a directory; null when the report carries no branches for it. */
    fun getBranchCoverageInformationString(file: VirtualFile, currentSuite: CoverageSuitesBundle): String? {
        val branches = indexFor(currentSuite)?.branches ?: return null
        val stat = branches[key(file.path)] ?: branches[key(file.canonicalPath ?: return null)] ?: return null
        val percent = stat.coveredBranchCount * 100 / stat.totalBranchCount
        return TestoBundle.message(
            "testo.coverage.view.branches.covered",
            percent,
            stat.coveredBranchCount,
            stat.totalBranchCount,
        )
    }

    private fun indexFor(bundle: CoverageSuitesBundle): Index? {
        val data = bundle.coverageData ?: return null
        synchronized(lock) {
            // RemappingCoverageAnnotator can swap the suite's data for a remapped copy, so compare identity, not content.
            if (indexedData !== data) {
                index = buildIndex(data, lineTotalsOf(bundle))
                indexedData = data
            }
            return index
        }
    }

    private fun lineTotalsOf(bundle: CoverageSuitesBundle): Map<String, LineTotals> =
        bundle.suites.filterIsInstance<TestoCoverageSuite>().flatMap { it.lineTotals.entries }.associate { it.toPair() }

    private fun buildIndex(data: ProjectData, lineTotals: Map<String, LineTotals>): Index {
        val files = HashMap<String, BaseCoverageAnnotator.FileCoverageInfo>()
        val branches = HashMap<String, BranchStat>()
        for ((path, classData) in data.classes) {
            // Data we did not build ourselves can hold a line-less ClassData, and fileInfoForCoveredFile NPEs on one.
            if (classData.lines == null) continue
            val info = fileInfoForCoveredFile(classData) ?: continue
            val filePath = key(path)
            files[filePath] = info
            branchStatFor(classData)?.let { branches[filePath] = it }
        }
        // Reported tallies win over the lines in the data, and add the files that have no covered line at all.
        for ((path, totals) in lineTotals) {
            files[key(path)] = BaseCoverageAnnotator.FileCoverageInfo().apply {
                totalLineCount = totals.total
                coveredLineCount = totals.executed
            }
        }
        return Index(files, aggregateDirs(files, branches), branches)
    }

    private fun aggregateDirs(
        files: Map<String, BaseCoverageAnnotator.FileCoverageInfo>,
        branches: MutableMap<String, BranchStat>,
    ): Map<String, BaseCoverageAnnotator.DirCoverageInfo> {
        val dirs = HashMap<String, BaseCoverageAnnotator.DirCoverageInfo>()
        for ((filePath, info) in files) {
            val branchStat = branches[filePath]
            var dir = filePath.substringBeforeLast('/', "")
            while (dir.isNotEmpty()) {
                val aggregate = dirs.getOrPut(dir) { BaseCoverageAnnotator.DirCoverageInfo() }
                aggregate.totalLineCount += info.totalLineCount
                aggregate.totalFilesCount++
                if (info.coveredLineCount > 0) {
                    aggregate.coveredLineCount += info.coveredLineCount
                    aggregate.coveredFilesCount++
                }
                if (branchStat != null) {
                    val branchAggregate = branches.getOrPut(dir) { BranchStat() }
                    branchAggregate.totalBranchCount += branchStat.totalBranchCount
                    branchAggregate.coveredBranchCount += branchStat.coveredBranchCount
                }
                dir = dir.substringBeforeLast('/', "")
            }
        }
        return dirs
    }

    private fun branchStatFor(classData: ClassData): BranchStat? {
        val stat = BranchStat()
        for (line in classData.lines ?: return null) {
            val branchData = (line as? LineData)?.branchData ?: continue
            stat.totalBranchCount += branchData.totalBranches
            stat.coveredBranchCount += branchData.coveredBranches
        }
        return stat.takeIf { it.totalBranchCount > 0 }
    }

    private fun key(path: String): String =
        FileUtil.toSystemIndependentName(path).let { if (SystemInfo.isWindows) it.lowercase() else it }

    companion object {
        fun getInstance(project: Project): TestoCoverageAnnotator = project.getService(TestoCoverageAnnotator::class.java)
    }
}
