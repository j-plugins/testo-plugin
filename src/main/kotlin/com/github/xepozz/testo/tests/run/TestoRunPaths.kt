package com.github.xepozz.testo.tests.run

import com.github.xepozz.testo.TestoComposerConfig
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

    // These paths are Windows-shaped but the unit tests and CI run on Linux, so every helper must be pure string
    // work: OSAgnosticPathUtil/PathUtilRt branch on the host OS (Platform.CURRENT feeds isWindowsUNCRoot).
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:(?:/|$)")

    private const val COMPOSER_MANIFEST = "composer.json"

    private data class Canonical(val distro: String?, val path: String)

    fun resolveWorkingDirectory(
        customWorkingDirectory: String?,
        configurationFilePath: String?,
        executableRoot: () -> String? = { null },
        fallback: () -> String?,
    ): String? {
        if (!customWorkingDirectory.isNullOrEmpty()) return customWorkingDirectory
        parentOfConfigurationFile(configurationFilePath)?.let { return it }
        executableRoot()?.let { return it }
        return fallback()
    }

    // The platform fallback is the content root, which a nested project's interpreter may not even map.
    fun projectRootOfExecutable(executable: String, basePath: String, exists: (String) -> Boolean): String? {
        // A globally installed binary (~/.composer/vendor/bin/testo) says nothing about this project's root.
        if (relativePath(executable, basePath) !is PathResolution.Relative) return null

        var directory = parentPath(FileUtil.toSystemIndependentName(executable).trimEnd('/'))
        while (directory != null) {
            if (exists(childPath(directory, TestoComposerConfig.DEFAULT_CONFIG_NAME))
                || exists(childPath(directory, COMPOSER_MANIFEST))
            ) return directory
            if (relativePath(directory, basePath) == PathResolution.WorkingDirectory) return null
            directory = parentPath(directory)
        }
        return null
    }

    private fun childPath(directory: String, name: String) =
        if (directory.endsWith('/')) "$directory$name" else "$directory/$name"

    fun parentOfConfigurationFile(configurationFilePath: String?): String? {
        if (configurationFilePath.isNullOrEmpty()) return null
        // Keep //wsl.localhost/… as is: LocalFileSystem on Windows cannot see the bare /home/… form.
        val independent = FileUtil.toSystemIndependentName(configurationFilePath)
        // Testo resolves a config's paths against getcwd(), not the file, so only the default testo.php marks the root.
        val name = independent.substringAfterLast('/')
        if (!name.equals(TestoComposerConfig.DEFAULT_CONFIG_NAME, ignoreCase = true)) return null
        return parentPath(independent)
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

    private fun startsWithWindowsDrive(path: String): Boolean = WINDOWS_DRIVE.containsMatchIn(path)

    // canonicalize has already peeled a WSL path down to its Linux inner path, so // here is a plain UNC share.
    private fun isWindowsForm(path: String): Boolean = startsWithWindowsDrive(path) || path.startsWith("//")

    private fun parentPath(independent: String): String? {
        val cut = independent.lastIndexOf('/')
        if (cut < 0) return null
        val parent = independent.substring(0, cut)
        return when {
            parent.isEmpty() -> "/"
            parent.length == 2 && startsWithWindowsDrive(parent) -> "$parent/"
            // A cut above //host/share leaves no valid parent; the share root itself (3 separators) is kept.
            independent.startsWith("//") && parent.count { it == '/' } < 3 -> null
            else -> parent
        }
    }

    private fun canonicalize(path: String): Canonical {
        val trimmed = FileUtil.toSystemIndependentName(path).trimEnd('/')
        // A trimmed drive root ("D:") is the drive's current directory, not its root; keep the slash.
        val independent =
            if (trimmed.length == 2 && startsWithWindowsDrive(trimmed)) "$trimmed/"
            else trimmed.ifEmpty { "/" }
        val match = WSL_UNC.matchEntire(independent) ?: return Canonical(null, independent)
        return Canonical(match.groupValues[1], match.groupValues[2].ifEmpty { "/" })
    }
}
