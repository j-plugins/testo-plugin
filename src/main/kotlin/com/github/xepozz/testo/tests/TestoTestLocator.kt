package com.github.xepozz.testo.tests

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.phpunit.LocationInfo
import com.jetbrains.php.phpunit.PhpUnitQualifiedNameLocationProvider
import com.jetbrains.php.util.pathmapper.PhpPathMapper

class TestoTestLocator(pathMapper: PhpPathMapper) :
    PhpUnitQualifiedNameLocationProvider(pathMapper) {
    override fun findElement(
        locationInfo: LocationInfo?,
        project: Project,
    ): LocationElementStore? {
        val locationFile = locationInfo?.file ?: return null
        val file = PsiManager.getInstance(project).findFile(locationFile) as? PhpFile ?: return null
        val className = locationInfo.className
        if (className.isNullOrEmpty()) {
            return LocationElementStore(file, null)
        }

        val classes = PhpPsiUtil.findAllClasses(file)
        // A standalone test function is named where a class would be. Checked before the classes, not only when the
        // file has none: a class miss answers with the file itself, so in a mixed file the jump landed on the file.
        if (classes.none { it.fqn == className }) {
            findFunction(file, className)?.let { return LocationElementStore(it, it) }
        }

        return classes
            .firstNotNullOfOrNull { clazz ->
                this.getLocation(
                    project,
                    locationFile,
                    clazz.fqn,
                    locationInfo.methodName,
                    null,
                )
            }
    }

    private fun findFunction(file: PhpFile, fqn: String): Function? =
        PsiTreeUtil.findChildrenOfType(file, Function::class.java).firstOrNull { it.fqn == fqn }

    /**
     * Examples:
     * - path/to/file.php
     * - path/to/file.php::\Full\Qualified\ClassName
     * - path/to/file.php::\Full\Qualified\ClassName::methodName
     * - path/to/file.php::\Full\Qualified\FunctionName
     *
     * The name in either of the last two positions may carry the data pointer Testo appends to reach one data set —
     * see [stripTestoCoordinates], which takes it back off.
     */
    public override fun getLocationInfo(link: String): LocationInfo? {
        val locations = link.split("::").dropLastWhile { it.isEmpty() }

        // The file is never stripped: a Windows path holds a colon of its own, and it names no PHP symbol anyway.
        return when (locations.size) {
            1 -> LocationInfo(null, null, this.myPathMapper.getLocalFile(locations[0]))
            2 -> LocationInfo(stripTestoCoordinates(locations[1]), null, this.myPathMapper.getLocalFile(locations[0]))
            3 -> LocationInfo(
                stripTestoCoordinates(locations[1]),
                stripTestoCoordinates(locations[2]),
                this.myPathMapper.getLocalFile(locations[0]),
            )

            else -> null
        }
    }
}

/**
 * The name a hint segment declares, without the coordinates appended to it.
 *
 * `\Ns\Calculator::med:3:0` names data set #0 of attribute #3; PHP declares no such member, so `findOwnMethodByName`
 * misses and the platform answers with the enclosing class — or with the file, for a standalone function. An
 * identifier cannot contain a colon, so the first one starts the coordinates.
 *
 * Navigation stops at the method: `:3` numbers the attribute within its own group, and which group that is comes from
 * the run's `--type`, which the hint does not carry.
 *
 * `#<index>` and ` with data set #N` are the plugin's own display suffixes and go too.
 */
fun stripTestoCoordinates(segment: String): String? = segment
    .substringBefore(" with data set")
    .substringBefore('#')
    .substringBefore(':')
    .trim()
    .ifEmpty { null }
