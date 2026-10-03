package com.github.xepozz.testo.phpstorm.tests

import com.github.xepozz.testo.tests.TestoLocationHints
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

    /** See [TestoLocationHints.parse]; the file comes back through the run's path mapper. */
    public override fun getLocationInfo(link: String): LocationInfo? {
        val parsed = TestoLocationHints.parse(link) ?: return null
        return LocationInfo(parsed.className, parsed.methodName, this.myPathMapper.getLocalFile(parsed.filePath))
    }
}
