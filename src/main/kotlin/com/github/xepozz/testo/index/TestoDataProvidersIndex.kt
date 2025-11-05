package com.github.xepozz.testo.index

import com.github.xepozz.testo.isTestoClass
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.PhpPsiUtil
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.stubs.indexes.expectedArguments.PhpExpectedFunctionArgument
import com.jetbrains.php.lang.psi.stubs.indexes.expectedArguments.PhpExpectedFunctionScalarArgument
import com.jetbrains.rd.util.printlnError
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

private typealias TestoDataProvidersIndexType = MutableSet<TestoDataProvidersIndex.DataProviderUsage>

class TestoDataProvidersIndex : FileBasedIndexExtension<String, TestoDataProvidersIndexType>() {
    override fun getName() = KEY

    override fun getIndexer() = DataIndexer<String, TestoDataProvidersIndexType, FileContent?> { inputData ->
        val map = mutableMapOf<String, TestoDataProvidersIndexType>()

        for (testClass in PhpPsiUtil.findAllClasses(inputData.psiFile)) {
            if (!testClass.isTestoClass()) continue
            for (method in testClass.ownMethods) {
                val dataProviders = getDataProvidersFromAttributes(method)

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

    override fun getVersion() = 1

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType is PhpFileType }

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
        private const val PHPUNIT_DATA_PROVIDER_ATTRIBUTE = "\\Testo\\Sample\\DataProvider"

        private fun getDataProvidersFromAttributes(method: Method): MutableSet<Pair<String, String>> {
            val result = mutableSetOf<Pair<String, String>>()

            val targetFQN = method.containingClass?.fqn ?: method.fqn

            for (dataProvider in method.getAttributes(PHPUNIT_DATA_PROVIDER_ATTRIBUTE)) {
                val argument = getAttributeArgument(dataProvider, "provider", 0) ?: continue
                val methodNameArg = argument as? PhpExpectedFunctionScalarArgument ?: continue
                val attributeValue = methodNameArg.value

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
                        printlnError("Unknown data provider type: $attributeValue")
//                        result.add(
//                            Pair.create(targetFQN, attributeValue)
//                        )
                    }
                }
            }

            return result
        }

        private fun getAttributeArgument(
            attribute: PhpAttribute,
            name: String,
            index: Int
        ): PhpExpectedFunctionArgument? {
            val arguments = attribute.arguments
            val argument = arguments.firstOrNull { it.name == name } ?: arguments.getOrNull(index)

            return argument?.argument
        }
    }
}
