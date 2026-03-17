package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoFrameworkType
import junit.framework.TestCase

class TestoFrameworkTypeTest : TestCase() {

    fun testConstants() {
        assertEquals("Testo", TestoFrameworkType.ID)
        assertEquals("php_qn", TestoFrameworkType.SCHEMA)
    }

    fun testGetID() {
        val type = TestoFrameworkType()
        assertEquals("Testo", type.id)
    }

    fun testComposerPackageNames() {
        val type = TestoFrameworkType()
        val packages = type.composerPackageNames
        assertTrue(packages.contains("php"))
    }

    fun testGetDescriptor() {
        val type = TestoFrameworkType()
        assertNotNull(type.descriptor)
    }
}
