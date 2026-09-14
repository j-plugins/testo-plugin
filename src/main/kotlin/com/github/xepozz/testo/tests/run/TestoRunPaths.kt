package com.github.xepozz.testo.tests.run

import com.intellij.openapi.util.io.FileUtil

/** `--path` is relative to the process cwd, so it is computed from local paths, off the interpreter's path mapper. */
object TestoRunPaths {
    sealed interface PathResolution {
        data class Relative(val path: String) : PathResolution

        object WorkingDirectory : PathResolution

        object Ancestor : PathResolution

        object Unrelated : PathResolution
    }

    private val WSL_UNC = Regex("^//wsl(?:\\.localhost|\\$)/([^/]+)(/.*)?$", RegexOption.IGNORE_CASE)

    private data class Canonical(val distro: String?, val path: String)

    fun relativePath(targetPath: String, workingDirectory: String): PathResolution {
        if (targetPath.isEmpty() || workingDirectory.isEmpty()) return PathResolution.Unrelated

        val target = canonicalize(targetPath)
        val base = canonicalize(workingDirectory)

        // A bare /home/… has no distro and matches any: PhpStorm on Windows stores one location in both forms.
        if (target.distro != null && base.distro != null && !target.distro.equals(base.distro, ignoreCase = true)) {
            return PathResolution.Unrelated
        }

        // Linux paths (incl. the WSL inner path) are case-sensitive, Windows drive paths are not.
        val caseSensitive = base.path.startsWith("/") && target.path.startsWith("/")
        val relative = FileUtil.getRelativePath(base.path, target.path, '/', caseSensitive)
            ?: return PathResolution.Unrelated

        return when {
            relative.isEmpty() || relative == "." -> PathResolution.WorkingDirectory
            relative == ".." || relative.startsWith("../") -> ancestorOrUnrelated(base.path, target.path, caseSensitive)
            else -> PathResolution.Relative(relative)
        }
    }

    private fun ancestorOrUnrelated(base: String, target: String, caseSensitive: Boolean): PathResolution {
        val reverse = FileUtil.getRelativePath(target, base, '/', caseSensitive)
        return if (reverse != null && reverse != ".." && !reverse.startsWith("../")) PathResolution.Ancestor
        else PathResolution.Unrelated
    }

    private fun canonicalize(path: String): Canonical {
        val independent = FileUtil.toSystemIndependentName(path).trimEnd('/')
        val match = WSL_UNC.matchEntire(independent) ?: return Canonical(null, independent)
        return Canonical(match.groupValues[1], match.groupValues[2].ifEmpty { "/" })
    }
}
