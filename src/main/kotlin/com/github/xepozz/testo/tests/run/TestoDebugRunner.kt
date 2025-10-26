package com.github.xepozz.testo.tests.run

import com.jetbrains.php.testFramework.run.PhpTestDebugRunner

class TestoDebugRunner : PhpTestDebugRunner<TestoRunConfiguration>(TestoRunConfiguration::class.java) {
    override fun getRunnerId() = "TestoDebugRunner"
}