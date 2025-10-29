package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.TestoIcons
import com.intellij.openapi.project.Project
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.testFramework.PhpEmptyTestDescriptor
import com.jetbrains.php.testFramework.PhpTestCreateInfo
import com.jetbrains.php.testFramework.PhpTestDescriptor
import com.jetbrains.php.testFramework.PhpTestFrameworkFormDecorator
import com.jetbrains.php.testFramework.PhpTestFrameworkFormDecorator.PhpDownloadableTestFormDecorator
import com.jetbrains.php.testFramework.PhpTestFrameworkType
import com.jetbrains.php.testFramework.PhpUnitTestCreateInfo
import com.jetbrains.php.testFramework.ui.PhpTestFrameworkBaseConfigurableForm
import com.jetbrains.php.testFramework.ui.PhpTestFrameworkConfigurableForm

class TestoFrameworkType : PhpTestFrameworkType() {
    override fun getDisplayName() = TestoBundle.message("testo.local.run.display.name")

    override fun getID() = ID

    override fun getIcon() = TestoIcons.TESTO

//    override fun getComposerPackageNames() = arrayOf("testo/testo")
    override fun getComposerPackageNames() = arrayOf("php")
//    override fun getComposerPackageNames() = emptyArray<String>()

    override fun getDescriptor(): PhpTestDescriptor {
        return object : PhpEmptyTestDescriptor() {
            override fun findMethods(testMethod: Method): Collection<Method?> {
                println("findMethods testMethod: ${testMethod.name}")
                return super.findMethods(testMethod)
            }
            override fun isTestClassName(name: String): Boolean {
                println("isTestClassName $name")
                return super.isTestClassName(name)
            }
            override fun findTests(method: Method): Collection<Method> {
                println("findTests: ${method.name}")
                val attributes = method.getAttributes(TestoClasses.TEST)
                println("attributes: $attributes")
                if (attributes.isNotEmpty()) {
                    return listOf(method)
                }
                return emptyList()
            }

            override fun getTestCreateInfos(): Collection<PhpTestCreateInfo?> {
                println("getTestCreateInfos")
                return listOf<PhpTestCreateInfo?>(PhpUnitTestCreateInfo())
            }
        }
    }

    override fun getDecorator(): PhpTestFrameworkFormDecorator {
        return object : PhpDownloadableTestFormDecorator("https://github.com/testo/testo/releases") {
            override fun decorate(
                project: Project?,
                form: PhpTestFrameworkBaseConfigurableForm<*>
            ): PhpTestFrameworkConfigurableForm<*> {
                form.setVersionDetector(TestoVersionDetector)
                return super.decorate(project, form)
            }
        }
    }

    companion object Companion {
        const val ID = "Testo"
        const val SCHEMA = "php_qn"
        val INSTANCE: TestoFrameworkType
            get() = getTestFrameworkType(ID) as TestoFrameworkType
    }
}
