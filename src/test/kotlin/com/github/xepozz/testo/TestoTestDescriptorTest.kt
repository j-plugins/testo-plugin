package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestDescriptor
import junit.framework.TestCase

class TestoTestDescriptorTest : TestCase() {

    fun testIsTestClassName_withTestSuffix() {
        assertTrue(TestoTestDescriptor.isTestClassName("UserTest"))
        assertTrue(TestoTestDescriptor.isTestClassName("SomeFeatureTest"))
    }

    fun testIsTestClassName_withTestBaseSuffix() {
        assertTrue(TestoTestDescriptor.isTestClassName("AbstractTestBase"))
        assertTrue(TestoTestDescriptor.isTestClassName("BaseFeatureTestBase"))
    }

    fun testIsTestClassName_withoutTestSuffix() {
        assertFalse(TestoTestDescriptor.isTestClassName("UserService"))
        assertFalse(TestoTestDescriptor.isTestClassName("TestHelper"))
        assertFalse(TestoTestDescriptor.isTestClassName("Testing"))
        assertFalse(TestoTestDescriptor.isTestClassName("Contest"))
    }

    fun testIsTestClassName_edgeCases() {
        assertTrue(TestoTestDescriptor.isTestClassName("Test"))
        assertTrue(TestoTestDescriptor.isTestClassName("TestBase"))
        assertFalse(TestoTestDescriptor.isTestClassName(""))
    }
}
