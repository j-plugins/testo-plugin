package com.github.xepozz.testo.tests.run

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PathUtil

/**
 * Path helpers for Testo CLI arguments that are *not* remote filesystem paths.
 *
 * `--path` is a Testo-relative selector against the process working directory. Computing it from local paths and
 * passing it as a plain argument keeps it out of the PHP interpreter's path mapper — which would otherwise leave
 * `--path` empty when the working directory sits outside the mapped Docker Compose root (PHP app in a subdirectory).
 */
object TestoRunPaths {
    /**
     * `//wsl.localhost/<distro>/…` or `//wsl$/<distro>/…` → the Linux path inside the distro (`/home/…`).
     *
     * PhpStorm on Windows stores some VirtualFile paths as WSL UNC and others as plain `/home/…`; relativize of a
     * mixed pair walks up through `../…` and must not be used as `--path`.
     */
    private val WSL_UNC = Regex("^//wsl(?:\\.localhost|\\$)/[^/]+(/.*)$", RegexOption.IGNORE_CASE)

    /**
     * Directory that should be the Testo process working directory, preferring an explicit custom WD, then the
     * parent of the active configuration file (`testo.php`), then [fallback] (typically the platform's composer /
     * content-root / project-base resolution).
     */
    fun resolveWorkingDirectory(
        customWorkingDirectory: String?,
        configurationFilePath: String?,
        fallback: () -> String?,
    ): String? {
        if (StringUtil.isNotEmpty(customWorkingDirectory)) return customWorkingDirectory
        parentOfConfigurationFile(configurationFilePath)?.let { return it }
        return fallback()
    }

    /** Parent directory of [configurationFilePath], or null when the path is missing / has no parent. */
    fun parentOfConfigurationFile(configurationFilePath: String?): String? {
        if (configurationFilePath.isNullOrEmpty()) return null
        // Keep the IDE's path form (incl. `//wsl.localhost/…`). Stripping to `/home/…` breaks
        // checkConfiguration on Windows — LocalFileSystem cannot see the bare Linux path.
        val parent = PathUtil.getParentPath(FileUtil.toSystemIndependentName(configurationFilePath))
        return parent.takeIf { it.isNotEmpty() }
    }

    /**
     * Relative path of [targetPath] under [workingDirectory], using `/` separators for the Testo CLI.
     *
     * Returns null when either side is empty, when [targetPath] is not under [workingDirectory], or when the
     * result would be empty (target equals the working directory — no `--path` needed).
     */
    fun relativePath(targetPath: String, workingDirectory: String): String? {
        if (targetPath.isEmpty() || workingDirectory.isEmpty()) return null

        // Prefer canonicalized forms so WSL UNC and `/home/…` compare equal; also try the raw independent forms in
        // case both sides already share one representation.
        return relativizeUnder(canonicalize(targetPath), canonicalize(workingDirectory))
            ?: relativizeUnder(
                FileUtil.toSystemIndependentName(targetPath),
                FileUtil.toSystemIndependentName(workingDirectory),
            )
    }

    /**
     * Collapse WSL UNC and backslashes into a stable `/`-separated path for comparisons.
     * Visible for tests.
     */
    fun canonicalize(path: String): String {
        val independent = FileUtil.toSystemIndependentName(path).trimEnd('/')
        return WSL_UNC.matchEntire(independent)?.groupValues?.get(1) ?: independent
    }

    private fun relativizeUnder(target: String, base: String): String? {
        if (target.isEmpty() || base.isEmpty()) return null
        val relative = FileUtil.getRelativePath(base, target, '/') ?: return null
        // Same directory → "."; outside the tree → "../…". Neither is a usable Testo --path.
        if (relative.isEmpty() || relative == "." || relative == "..") return null
        if (relative.startsWith("../")) return null
        return relative
    }
}
