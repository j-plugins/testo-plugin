package com.github.xepozz.testo.index

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.isTestoClass
import com.github.xepozz.testo.php.PhpFunctionView
import com.github.xepozz.testo.php.TestoPhp
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.openapi.diagnostic.thisLogger
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

private typealias TestoDataProvidersIndexType = MutableSet<TestoDataProvidersIndex.DataProviderUsage>

class TestoDataProvidersIndex : FileBasedIndexExtension<String, TestoDataProvidersIndexType>() {
    override fun getName() = KEY

    override fun getIndexer() = DataIndexer<String, TestoDataProvidersIndexType, FileContent?> { inputData ->
        val map = mutableMapOf<String, TestoDataProvidersIndexType>()

        for (testClass in TestoPhp.getInstance().allClasses(inputData.psiFile)) {
            // An indexer must not query the global class index: it loads other files' stubs mid-indexing.
            if (!testClass.psi.isTestoClass(resolveHierarchy = false)) continue
            for (method in testClass.ownMethods) {
                val dataProviders = getDataProvidersFromAttributes(method.psi)

                for (dataProvider in dataProviders) {
                    map.computeIfAbsent(dataProvider.second) { mutableSetOf() }
                        .add(DataProviderUsage(testClass.fqn, method.name, dataProvider.first))
                }
            }
        }

        map
    }

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<TestoDataProvidersIndexType> =
        DataProviderUsageExternalizer.INSTANCE

    // Bump on any indexing-logic change so stale on-disk indexes rebuild: 2 corrected DATA_PROVIDER_ATTRIBUTE;
    // 3 stopped resolving subclasses.
    override fun getVersion() = 3

    override fun getInputFilter() = PHP_INPUT_FILTER

    override fun dependsOnFileContent() = true

    @JvmRecord
    data class DataProviderUsage(val classFqn: String, val methodName: String, val dataProviderFqn: String?)

    internal class DataProviderUsageExternalizer : DataExternalizer<TestoDataProvidersIndexType> {
        @Throws(IOException::class)
        override fun save(out: DataOutput, value: TestoDataProvidersIndexType) {
            out.writeInt(value.size)

            for (item in value) {
                saveItem(out, item)
            }
        }

        @Throws(IOException::class)
        override fun read(`in`: DataInput): TestoDataProvidersIndexType {
            val size = `in`.readInt()
            val result: HashSet<DataProviderUsage> = HashSet(size)

            for (i in 0..<size) {
                result.add(readItem(`in`))
            }

            return result
        }

        companion object Companion {
            val INSTANCE: DataProviderUsageExternalizer = DataProviderUsageExternalizer()

            @Throws(IOException::class)
            private fun readItem(`in`: DataInput): DataProviderUsage {
                val classFqn = `in`.readUTF()
                val methodName = `in`.readUTF()
                val dataProviderFqn = StringUtil.nullize(`in`.readUTF())
                return DataProviderUsage(classFqn, methodName, dataProviderFqn)
            }

            @Throws(IOException::class)
            private fun saveItem(out: DataOutput, value: DataProviderUsage) {
                out.writeUTF(value.classFqn)
                out.writeUTF(value.methodName)
                out.writeUTF(StringUtil.notNullize(value.dataProviderFqn))
            }
        }
    }

    companion object Companion {
        val KEY = ID.create<String, TestoDataProvidersIndexType>("Testo.DataProviders")
        // The real Testo data-provider attribute; the previous "\Testo\Sample\DataProvider" matched nothing.
        private const val DATA_PROVIDER_ATTRIBUTE = TestoClasses.DATA_PROVIDER

        fun getDataProvidersFromAttributes(function: PsiElement): MutableSet<Pair<String, String>> {
            val result = mutableSetOf<Pair<String, String>>()
            val view = TestoPhp.getInstance().view(function) as? PhpFunctionView ?: return result

            val targetFQN = view.containingClass?.fqn ?: view.fqn

            for (dataProvider in view.attributes(DATA_PROVIDER_ATTRIBUTE)) {
                val methodNameArg = dataProvider.argument("provider", 0) ?: continue
                val attributeValue = methodNameArg.text

                when {
                    methodNameArg.isStringLiteral -> result.add(
                        Pair.create(targetFQN, StringUtil.unquoteString(attributeValue))
                    )

                    attributeValue.startsWith("[") && attributeValue.endsWith("]") -> {
                        // todo: replace with PSI creation
                        val classMethodPair = attributeValue
                            .substring(1, attributeValue.length - 1)
                            .split(",")
                            .map { it.trim() }
                        if (classMethodPair.size != 2) continue

                        val classFQN = when {
                            classMethodPair.first() in arrayOf("self::class", "static::class") -> targetFQN
                            else -> classMethodPair.first()
                        }

                        result.add(
                            Pair.create(classFQN, StringUtil.unquoteString(classMethodPair.last()))
                        )
                    }

                    else -> {
                        thisLogger().debug("Unknown data provider type: $attributeValue")
//                        result.add(
//                            Pair.create(targetFQN, attributeValue)
//                        )
                    }
                }
            }

            return result
        }
    }
}
