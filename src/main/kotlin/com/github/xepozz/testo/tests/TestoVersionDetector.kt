package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoBundle
import com.intellij.execution.ExecutionException
import com.jetbrains.php.PhpTestFrameworkVersionDetector

object TestoVersionDetector : PhpTestFrameworkVersionDetector<String>() {
    override fun getPresentableName() = TestoBundle.message("testo.local.run.display.name")

    override fun getVersionOptions() = arrayOf("--version", "--no-ansi")

    public override fun parse(s: String): String {
        val version = s.substringAfter("Testo ")
        if (version.isEmpty()) {
            throw ExecutionException(TestoBundle.message("testo.version.error"))
        }
        return version
    }
}