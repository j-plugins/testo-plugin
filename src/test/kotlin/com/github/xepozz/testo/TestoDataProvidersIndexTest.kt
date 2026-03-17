package com.github.xepozz.testo

import com.github.xepozz.testo.index.TestoDataProvidersIndex
import com.intellij.openapi.util.Pair
import junit.framework.TestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class TestoDataProvidersIndexTest : TestCase() {

    fun testIndexKey() {
        assertEquals("Testo.DataProviders", TestoDataProvidersIndex.KEY.name)
    }

    fun testDataProviderUsage_creation() {
        val usage = TestoDataProvidersIndex.DataProviderUsage(
            "\\App\\Tests\\UserTest",
            "testCreate",
            "\\App\\Tests\\UserTest"
        )
        assertEquals("\\App\\Tests\\UserTest", usage.classFqn)
        assertEquals("testCreate", usage.methodName)
        assertEquals("\\App\\Tests\\UserTest", usage.dataProviderFqn)
    }

    fun testDataProviderUsage_nullDataProviderFqn() {
        val usage = TestoDataProvidersIndex.DataProviderUsage(
            "\\App\\Tests\\FooTest",
            "testBar",
            null
        )
        assertNull(usage.dataProviderFqn)
    }

    fun testDataProviderUsage_equality() {
        val usage1 = TestoDataProvidersIndex.DataProviderUsage("\\FooTest", "testA", "\\FooTest")
        val usage2 = TestoDataProvidersIndex.DataProviderUsage("\\FooTest", "testA", "\\FooTest")
        val usage3 = TestoDataProvidersIndex.DataProviderUsage("\\FooTest", "testB", "\\FooTest")

        assertEquals(usage1, usage2)
        assertFalse(usage1 == usage3)
        assertEquals(usage1.hashCode(), usage2.hashCode())
    }

    fun testExternalizer_roundTrip() {
        val externalizer = TestoDataProvidersIndex.DataProviderUsageExternalizer.INSTANCE

        val original = mutableSetOf(
            TestoDataProvidersIndex.DataProviderUsage("\\Tests\\UserTest", "testCreate", "\\Tests\\UserTest"),
            TestoDataProvidersIndex.DataProviderUsage("\\Tests\\OrderTest", "testProcess", null),
        )

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        externalizer.save(out, original)
        out.flush()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val inp = DataInputStream(bais)
        val restored = externalizer.read(inp)

        assertEquals("Restored set should have same size", original.size, restored.size)
        for (item in original) {
            assertTrue("Restored set should contain $item", restored.contains(item))
        }
    }

    fun testExternalizer_emptySet() {
        val externalizer = TestoDataProvidersIndex.DataProviderUsageExternalizer.INSTANCE

        val original = mutableSetOf<TestoDataProvidersIndex.DataProviderUsage>()

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        externalizer.save(out, original)
        out.flush()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val inp = DataInputStream(bais)
        val restored = externalizer.read(inp)

        assertTrue("Restored set should be empty", restored.isEmpty())
    }

    fun testExternalizer_nullDataProviderFqn_roundTrip() {
        val externalizer = TestoDataProvidersIndex.DataProviderUsageExternalizer.INSTANCE

        val original = mutableSetOf(
            TestoDataProvidersIndex.DataProviderUsage("\\Tests\\Foo", "testBar", null),
        )

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        externalizer.save(out, original)
        out.flush()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val inp = DataInputStream(bais)
        val restored = externalizer.read(inp)

        assertEquals(1, restored.size)
        val restoredItem = restored.first()
        assertEquals("\\Tests\\Foo", restoredItem.classFqn)
        assertEquals("testBar", restoredItem.methodName)
        // null is stored as empty string by StringUtil.notNullize, restored as null by StringUtil.nullize
        assertNull("Null dataProviderFqn should survive round-trip", restoredItem.dataProviderFqn)
    }

    fun testIndexVersion() {
        val index = TestoDataProvidersIndex()
        assertTrue("Index version should be positive", index.version > 0)
    }

    fun testDependsOnFileContent() {
        val index = TestoDataProvidersIndex()
        assertTrue("Index should depend on file content", index.dependsOnFileContent())
    }
}
