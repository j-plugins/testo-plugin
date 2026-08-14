package com.github.xepozz.testo.coverage.perTest

import com.intellij.openapi.util.SystemInfo

/**
 * One canonical spelling for a source file used as the per-test index key. Forward slashes always; lower-cased on
 * Windows, where the file system is case-insensitive and a report path (`D:\…`) and a VFS path may differ only in case.
 * Mirrors the intent of the platform's `SimpleCoverageAnnotator.normalizeFilePath` (which is `protected`, so ours).
 */
object TestoCoverageKeys {
    fun normalize(path: String): String {
        val slashed = path.replace('\\', '/')
        return if (SystemInfo.isWindows) slashed.lowercase() else slashed
    }
}
