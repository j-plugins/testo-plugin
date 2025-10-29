package com.github.xepozz.testo.services

import com.github.xepozz.testo.tests.TestoFrameworkType
import com.intellij.execution.TestStateStorage
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.findParentOfType
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.testFramework.PhpTestFrameworkFailedLineManager

@Service(Service.Level.PROJECT)
class TestoFailedLineManager(
    project: Project
) : PhpTestFrameworkFailedLineManager(project), FileEditorManagerListener {
    override fun getTestLocationUrl(testElement: PsiElement): String? {
        if (testElement !is FunctionReference) return null
        val function = testElement.findParentOfType<Function>() ?: return null
        val locationUrl = getLocationUrl(testElement.containingFile, function)
//        println("getTestLocationUrl: $locationUrl, ${testElement}")
        return locationUrl
    }

    override fun getRecordsForTest(testElement: PsiElement): List<TestStateStorage.Record> {
        val testStateStorage = TestStateStorage.getInstance(testElement.project)

        val testLocationUrl = getTestLocationUrl(testElement) ?: return emptyList()
        val testStateRecord = testStateStorage.getState(testLocationUrl) ?: return emptyList()

        if (!testStateRecord.failedMethod.contains((testElement as MethodReference).text)) {
            return emptyList()
        }

        val records = mutableListOf(testStateRecord)
        if (testStateRecord.failedLine == -1) {
            val allRecordLocationUrls = testStateStorage.keys
            val dataSetRecords = allRecordLocationUrls
                .asSequence()
                .filter { recordLocationUrl -> isLocationUrlWithNamedDatasetValue(recordLocationUrl, testLocationUrl) }
                .map { recordLocationUrl -> testStateStorage.getState(recordLocationUrl) }
                .filterNotNull()
                .filter { record -> record.failedLine != -1 }
                .toList()

            records.addAll(dataSetRecords)
        }

        return records
    }

    private fun isLocationUrlWithNamedDatasetValue(recordLocationUrl: String, testLocationUrl: String): Boolean =
        recordLocationUrl.startsWith("$testLocationUrl with data set \"dataset")

    private fun getLocationUrl(containingFile: PsiFile, functionCall: PhpNamedElement): String =
        getLocationUrl(containingFile) + "::" + getElementFqn(functionCall)

    private fun getLocationUrl(psiFile: PsiFile) = "${TestoFrameworkType.SCHEMA}://${psiFile.virtualFile.path}"

    companion object {

        private fun getElementFqn(functionCall: PhpNamedElement) = when (functionCall) {
            is Method -> functionCall.fqn.replace(".", "::")
            else -> functionCall.fqn
        }
    }
}
