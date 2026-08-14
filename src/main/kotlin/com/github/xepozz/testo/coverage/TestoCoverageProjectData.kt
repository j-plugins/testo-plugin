package com.github.xepozz.testo.coverage

import com.github.xepozz.testo.coverage.format.BranchCoverage
import com.github.xepozz.testo.coverage.format.ParsedReport
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData

/**
 * Builds the platform coverage model from a parsed report, mirroring how PHP's own `PhpCloverXMLOutputParser` populates
 * a [ProjectData]: one `ClassData` per file keyed by the forward-slashed source path (the annotator normalizes and, on
 * Windows, lower-cases both sides itself, so no further normalization here), lines laid out in a number-indexed array.
 *
 * Branch data is approximate by construction — Cobertura reports only `covered/total`, not *which* outcomes — so a
 * two-way line becomes a [com.intellij.rt.coverage.data.JumpData] and an n-way line a
 * [com.intellij.rt.coverage.data.SwitchData]; touching the default slot only when fully covered keeps a fully-covered
 * decision line green rather than partial. See `docs/coverage/report-formats.md` §2 and architecture §14.2.
 */
fun ParsedReport.toProjectData(): ProjectData {
    val projectData = ProjectData()
    for (file in files) {
        val classData = projectData.getOrCreateClassData(file.filePath)
        val executable = file.lines.filter { it.line >= 0 }
        if (executable.isEmpty()) continue
        val lines = arrayOfNulls<LineData>(executable.maxOf { it.line } + 1)
        for (lc in executable) {
            val lineData = LineData(lc.line, null)
            lineData.setHits(lc.hits)
            lc.branch?.let { applyBranch(lineData, it) }
            lines[lc.line] = lineData
        }
        classData.setLines(lines)
    }
    return projectData
}

private fun applyBranch(line: LineData, branch: BranchCoverage) {
    val total = branch.total
    if (total <= 0) return
    val covered = branch.covered.coerceIn(0, total)
    if (total == 2) {
        val jump = line.addJump(0)
        if (covered >= 1) jump.touchTrueHit()
        if (covered >= 2) jump.touchFalseHit()
    } else {
        val switch = line.addSwitch(0, IntArray(total) { it })
        for (i in 0 until covered) switch.touch(i)
        if (covered >= total) switch.touch(-1)
    }
    // getJumps()/getSwitches() (read by getStatus/getBranchData) return the array fields, null until fillArrays swaps
    // the builder lists into them.
    line.fillArrays()
}
