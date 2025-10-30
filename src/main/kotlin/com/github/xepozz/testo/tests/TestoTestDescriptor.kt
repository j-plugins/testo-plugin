package com.github.xepozz.testo.tests

import com.github.xepozz.testo.TestoClasses
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.testFramework.PhpEmptyTestDescriptor

object TestoTestDescriptor : PhpEmptyTestDescriptor() {
    override fun findMethods(testMethod: Method): Collection<Method?> {
        println("findMethods testMethod: ${testMethod.name}")
        return super.findMethods(testMethod)
    }

    override fun isTestClassName(name: String) = name.endsWith("Test") || name.endsWith("TestBase")

    override fun findTests(method: Method): Collection<Method> {
        println("findTests: ${method.name}")
        val attributes = method.getAttributes(TestoClasses.TEST)
        println("attributes: $attributes")
        if (attributes.isNotEmpty()) {
            return listOf(method)
        }
        return emptyList()
    }

    override fun getTestCreateInfos() = listOf(TestoTestCreateInfo.INSTANCE)
}