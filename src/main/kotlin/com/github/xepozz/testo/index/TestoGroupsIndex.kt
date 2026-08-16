package com.github.xepozz.testo.index

import com.github.xepozz.testo.TestoClasses
import com.github.xepozz.testo.tests.run.TestoRunConfigurationProducer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.BooleanDataDescriptor
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.elements.PhpAttribute

/**
 * Every group name a `#[\Testo\Filter\Group]` in the project spells, so the run configuration can offer them instead
 * of asking the user to remember what exists. The attribute is variadic and may sit on a class, a method or a
 * function, so one file can declare any number of names.
 *
 * The value is a placeholder — the key is the whole payload.
 */
class TestoGroupsIndex : FileBasedIndexExtension<String, Boolean>() {
    override fun getName() = KEY

    override fun getIndexer() = DataIndexer<String, Boolean, FileContent> { inputData ->
        // Attribute lookup resolves imports; skipping files that cannot mention the attribute keeps that off the
        // indexing path for the vast majority of a project's PHP.
        if (!inputData.contentAsText.contains(ATTRIBUTE_SHORT_NAME)) return@DataIndexer emptyMap()

        groupNamesIn(inputData.psiFile).associateWith { true }
    }

    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<Boolean> = BooleanDataDescriptor.INSTANCE

    override fun getVersion() = 1

    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType is PhpFileType }

    override fun dependsOnFileContent() = true

    companion object Companion {
        val KEY = ID.create<String, Boolean>("Testo.Groups")

        private const val ATTRIBUTE_SHORT_NAME = "Group"

        fun groupNamesIn(file: PsiFile): Set<String> =
            PsiTreeUtil.findChildrenOfType(file, PhpAttribute::class.java)
                .filter { it.fqn == TestoClasses.FILTER_GROUP }
                .flatMapTo(mutableSetOf()) { TestoRunConfigurationProducer.extractGroupNames(it) }

        /** Every group declared in the project, deduplicated and sorted. Empty while the index is still building. */
        fun allGroups(project: Project): List<String> {
            if (DumbService.isDumb(project)) return emptyList()

            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.projectScope(project)
            return runCatching {
                ReadAction.compute<List<String>, RuntimeException> {
                    val names = sortedSetOf<String>()
                    // The processor's own result stops the walk, so it must not be `add`'s "was new".
                    index.processAllKeys(KEY, { names.add(it); true }, scope, null)
                    // That walk answers from the whole on-disk index — one IDE shares it across every project and
                    // library it has ever indexed, and the scope is only a hint there. A name is this project's own
                    // only if a file in scope still holds it.
                    names.filter { index.getContainingFiles(KEY, it, scope).isNotEmpty() }
                }
            }.getOrDefault(emptyList())
        }
    }
}
