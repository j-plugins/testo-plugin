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
        if (locationInfo.className.isNullOrEmpty()) {
            return LocationElementStore(file, null)
        }

        val classes = PhpPsiUtil.findAllClasses(file)
        if (classes.isEmpty()) {
            return PsiTreeUtil.findChildrenOfType(file, Function::class.java)
                .firstOrNull {
                    it.fqn == "\\" + locationInfo.className
                }
                ?.let {
                    LocationElementStore(
                        it,
                        it,
                    )
                }
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


    override fun getLocationInfo(link: String): LocationInfo? {
        val locations = link.split("::").dropLastWhile { it.isEmpty() }

        return when (locations.size) {
            1 -> LocationInfo(null, null, this.myPathMapper.getLocalFile(locations[0]))
            2 -> LocationInfo(locations[1], null, this.myPathMapper.getLocalFile(locations[0]))
            3 -> LocationInfo(locations[1], locations[2], this.myPathMapper.getLocalFile(locations[0]))
            else -> null
        }
    }
}
