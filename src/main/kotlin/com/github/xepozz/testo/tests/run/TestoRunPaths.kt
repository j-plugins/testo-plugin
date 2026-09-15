package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoComposerConfig
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.util.PathUtil

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

    fun resolveWorkingDirectory(
        customWorkingDirectory: String?,
        configurationFilePath: String?,
        fallback: () -> String?,
    ): String? {
        if (!customWorkingDirectory.isNullOrEmpty()) return customWorkingDirectory
        parentOfConfigurationFile(configurationFilePath)?.let { return it }
        return fallback()
    }

    fun parentOfConfigurationFile(configurationFilePath: String?): String? {
        if (configurationFilePath.isNullOrEmpty()) return null
        // Keep //wsl.localhost/… as is: LocalFileSystem on Windows cannot see the bare /home/… form.
        val independent = FileUtil.toSystemIndependentName(configurationFilePath)
        // Testo resolves a config's paths against getcwd(), not the file, so only the default testo.php marks the root.
        val name = PathUtil.getFileName(independent)
        if (!name.equals(TestoComposerConfig.DEFAULT_CONFIG_NAME, ignoreCase = true)) return null
        return OSAgnosticPathUtil.getParent(independent)
    }

    fun relativePath(targetPath: String, workingDirectory: String): PathResolution {
        if (targetPath.isEmpty() || workingDirectory.isEmpty()) return PathResolution.Unrelated

        val target = canonicalize(targetPath)
        val base = canonicalize(workingDirectory)

        // A bare /home/… has no distro and matches any: PhpStorm on Windows stores one location in both forms.
        if (target.distro != null && base.distro != null && !target.distro.equals(base.distro, ignoreCase = true)) {
            return PathResolution.Unrelated
        }

        // Linux and WSL inner paths are case-sensitive even on a Windows host; only Windows drive/UNC forms are not.
        val caseSensitive = !isWindowsForm(base.path) && !isWindowsForm(target.path)
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

    // canonicalize has already peeled a WSL path down to its Linux inner path, so isUncPath cannot misfire on it.
    private fun isWindowsForm(path: String): Boolean =
        OSAgnosticPathUtil.startsWithWindowsDrive(path) || OSAgnosticPathUtil.isUncPath(path)

    private fun canonicalize(path: String): Canonical {
        val trimmed = FileUtil.toSystemIndependentName(path).trimEnd('/')
        // A trimmed drive root ("D:") is the drive's current directory, not its root; keep the slash.
        val independent =
            if (OSAgnosticPathUtil.startsWithWindowsDrive(trimmed) && trimmed.length == 2) "$trimmed/"
            else trimmed.ifEmpty { "/" }
        val match = WSL_UNC.matchEntire(independent) ?: return Canonical(null, independent)
        return Canonical(match.groupValues[1], match.groupValues[2].ifEmpty { "/" })
    }
}
