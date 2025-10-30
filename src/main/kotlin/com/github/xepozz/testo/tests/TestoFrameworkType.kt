package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.github.xepozz.testo.TestoIcons
import com.intellij.openapi.project.Project
import com.jetbrains.php.testFramework.PhpTestFrameworkFormDecorator
import com.jetbrains.php.testFramework.PhpTestFrameworkFormDecorator.PhpDownloadableTestFormDecorator
import com.jetbrains.php.testFramework.PhpTestFrameworkType
import com.jetbrains.php.testFramework.ui.PhpTestFrameworkBaseConfigurableForm
import com.jetbrains.php.testFramework.ui.PhpTestFrameworkConfigurableForm

class TestoFrameworkType : PhpTestFrameworkType() {
    override fun getDisplayName() = TestoBundle.message("testo.local.run.display.name")

    override fun getID() = ID

    override fun getIcon() = TestoIcons.TESTO

//    override fun getComposerPackageNames() = arrayOf("testo/testo")
    override fun getComposerPackageNames() = arrayOf("php")

    override fun getDescriptor() = TestoTestDescriptor

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
