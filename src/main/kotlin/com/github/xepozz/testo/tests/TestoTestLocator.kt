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
        // A standalone test function is named where a class would be, and no class lookup will ever find it. Checked
        // before the classes rather than only when the file has none: the platform answers a class miss with the file
        // itself, so in a file holding both, jumping to a function used to land on the file (or on the first class).
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
 * The name a segment of a location hint actually declares, without the coordinates appended to it.
 *
 * Testo points at one data set by spelling out the test it belongs to and then its position:
 * `\Ns\Calculator::med:3:0` — attribute #3 of `med`, and its data set #0. PHP declares no such member, so handing the
 * whole thing to the platform makes `findOwnMethodByName` miss, and it answers a miss with the enclosing class — or,
 * when the hint named a standalone function, with the file. Either way Jump to Source lands nowhere near the test.
 * A PHP identifier cannot contain a colon, so the first one starts the coordinates.
 *
 * Navigation stops at the method rather than reaching the data set itself. `:3` numbers the attribute *within its own
 * group* — data, inline and bench are counted independently (`PsiUtil.ATTRIBUTE_GROUPS`) — and which group that is
 * comes from the run's `--type`, which the hint does not carry. So `:3` alone cannot pick an attribute, and without
 * the attribute there is no provider to count `:0` into.
 *
 * The two suffixes the plugin's own hints can carry are dropped as well: `#<index>` from the line markers and
 * ` with data set #N` from Testo's node names, neither of which is part of a name either.
 */
fun stripTestoCoordinates(segment: String): String? = segment
    .substringBefore(" with data set")
    .substringBefore('#')
    .substringBefore(':')
    .trim()
    .ifEmpty { null }
