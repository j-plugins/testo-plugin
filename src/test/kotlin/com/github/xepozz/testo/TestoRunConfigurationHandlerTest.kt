package com.github.xepozz.testo

import com.github.xepozz.testo.tests.run.TestoRunConfigurationHandler
import junit.framework.TestCase

class TestoRunConfigurationHandlerTest : TestCase() {

    fun testGetConfigFileOption() {
        assertEquals("--config", TestoRunConfigurationHandler.INSTANCE.configFileOption)
    }

    fun testSingletonInstance() {
        assertSame(TestoRunConfigurationHandler.INSTANCE, TestoRunConfigurationHandler.INSTANCE)
    }
}
