package com.github.xepozz.testo

import com.github.xepozz.testo.tests.TestoTestLocator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.util.pathmapper.PhpPathMapper

class TestoTestLocatorTest : BasePlatformTestCase() {

    private lateinit var locator: TestoTestLocator

    override fun setUp() {
        super.setUp()
        locator = TestoTestLocator(PhpPathMapper.create(emptyList()))
    }

    fun testGetLocationInfo_fileOnly() {
        val info = locator.getLocationInfo("path/to/file.php")
        assertNotNull(info)
        assertNull("className should be null for file-only link", info!!.className)
        assertNull("methodName should be null for file-only link", info.methodName)
    }

    fun testGetLocationInfo_fileAndClass() {
        val info = locator.getLocationInfo("path/to/file.php::\\Full\\Qualified\\ClassName")
        assertNotNull(info)
        assertEquals("\\Full\\Qualified\\ClassName", info!!.className)
        assertNull("methodName should be null when only class specified", info.methodName)
    }

    fun testGetLocationInfo_fileClassAndMethod() {
        val info = locator.getLocationInfo("path/to/file.php::\\Full\\Qualified\\ClassName::methodName")
        assertNotNull(info)
        assertEquals("\\Full\\Qualified\\ClassName", info!!.className)
        assertEquals("methodName", info.methodName)
    }

    fun testGetLocationInfo_emptyString() {
        val info = locator.getLocationInfo("")
        assertNotNull("Empty string should still produce a result (single part)", info)
    }

    fun testGetLocationInfo_tooManyParts() {
        val info = locator.getLocationInfo("a::b::c::d")
        assertNull("Link with more than 3 parts should return null", info)
    }

    fun testGetLocationInfo_functionLink() {
        val info = locator.getLocationInfo("path/to/file.php::\\Full\\Qualified\\FunctionName")
        assertNotNull(info)
        assertEquals("\\Full\\Qualified\\FunctionName", info!!.className)
        assertNull(info.methodName)
    }
}
