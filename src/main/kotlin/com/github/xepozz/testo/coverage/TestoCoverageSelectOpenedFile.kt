package com.github.xepozz.testo.coverage

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import java.lang.ref.WeakReference
import javax.swing.JTree

/**
 * Selects the file open in the editor in the Coverage view's tree — our answer to the platform's *Always select
 * opened element*, which cannot work here.
 *
 * That one hands `CoverageViewExtension.getElementToSelect` the PSI **leaf** under the caret and then looks for a tree
 * node whose value equals it; every node of a directory-based coverage view holds a file or a directory, so nothing
 * ever matches. The mapper is `@ApiStatus.Internal`, and so are the view's own select call and its tree, so the fix is
 * to do the walk ourselves: the tree is reached through the toolbar's target component, and `TreeUtil.promiseSelect`
 * expands the async model along the way.
 */
@Service(Service.Level.PROJECT)
class TestoCoverageSelectOpenedFile(private val project: Project) : Disposable {

    // Whichever Coverage view last showed our toolbar. The service outlives any single view; the reference is weak so
    // a closed view is collected, and a stale one is caught by isShowing.
    @Volatile
    private var tree: WeakReference<JTree>? = null

    var enabled: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(KEY, false)
        set(value) {
            PropertiesComponent.getInstance(project).setValue(KEY, value, false)
            if (value) selectCurrentFile()
        }

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (enabled) select(event.newFile)
                }
            },
        )
    }

    /** Called from the toolbar action's update, which is the one place the view's own component is handed to us. */
    fun rememberTree(candidate: JTree) {
        if (tree?.get() !== candidate) tree = WeakReference(candidate)
    }

    private fun selectCurrentFile() {
        select(FileEditorManager.getInstance(project).selectedEditor?.file)
    }

    private fun select(file: VirtualFile?) {
        if (file == null || file.isDirectory) return
        val tree = tree?.get()?.takeIf { it.isShowing } ?: return
        TreeUtil.promiseSelect(tree, TreeVisitor { path ->
            ReadAction.compute<TreeVisitor.Action, RuntimeException> {
                when (val value = TreeUtil.getLastUserObject(AbstractTreeNode::class.java, path)?.value) {
                    is PsiFile -> if (value.virtualFile == file) TreeVisitor.Action.INTERRUPT else TreeVisitor.Action.SKIP_CHILDREN
                    // Descend only where the file can actually be, so no directory is expanded for nothing.
                    is PsiDirectory -> when {
                        VfsUtilCore.isAncestor(value.virtualFile, file, false) -> TreeVisitor.Action.CONTINUE
                        else -> TreeVisitor.Action.SKIP_CHILDREN
                    }
                    else -> TreeVisitor.Action.SKIP_CHILDREN
                }
            }
        })
    }

    override fun dispose() = Unit

    companion object {
        private const val KEY = "testo.coverage.view.selectOpenedFile"

        fun getInstance(project: Project): TestoCoverageSelectOpenedFile =
            project.getService(TestoCoverageSelectOpenedFile::class.java)
    }
}
