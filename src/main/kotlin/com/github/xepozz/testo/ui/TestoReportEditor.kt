package com.github.xepozz.testo.ui

import com.github.xepozz.testo.TestoBundle
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.beans.PropertyChangeListener
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * An editor tab showing a generated Testo report in JCEF, over `file://`. The platform's own `HTMLEditorProvider`
 * is `@ApiStatus.Internal` — hence the light file plus provider below, which is all public API.
 */
class TestoReportVirtualFile(val reportPath: Path, label: String) : LightVirtualFile(label) {
    init {
        isWritable = false
    }

    val reportUrl: String get() = reportPath.toUri().toString()
}

class TestoReportFileEditor(private val file: TestoReportVirtualFile) : UserDataHolderBase(), FileEditor {

    // Null when the IDE runs without JCEF; the action checks TestoReportViewer.isAvailable before opening a tab.
    private val browser: JBCefBrowser? = runCatching {
        if (JBCefApp.isSupported()) JBCefBrowser.createBuilder().setUrl(file.reportUrl).build() else null
    }.getOrNull()

    private val fallback: JComponent by lazy {
        JBLabel(TestoBundle.message("testo.report.webview.unavailable"), SwingConstants.CENTER)
            .apply { border = JBUI.Borders.empty(20) }
    }

    override fun getComponent(): JComponent = browser?.component ?: fallback

    override fun getPreferredFocusedComponent(): JComponent? = browser?.component

    override fun getName(): String = TestoBundle.message("testo.report.editor.name")

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    // Always valid: the file is regenerated in place by the next run, and answering false would close the tab.
    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    /**
     * Re-reads the report from disk. Through `loadURL`, not `cefBrowser.reloadIgnoreCache()`: `org.cef` is not on the
     * compile classpath (only `com.intellij.ui.jcef` is), and navigating to the same `file://` URL re-reads it anyway.
     */
    fun reload() {
        browser?.loadURL(file.reportUrl)
    }

    override fun dispose() {
        browser?.let { Disposer.dispose(it) }
    }
}

class TestoReportFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is TestoReportVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TestoReportFileEditor(file as TestoReportVirtualFile)

    override fun getEditorTypeId(): String = "TestoReportEditor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

object TestoReportViewer {
    // One light file per report path: FileEditorManager keys tabs by VirtualFile identity, not by equality.
    private val files = ConcurrentHashMap<String, TestoReportVirtualFile>()

    /**
     * Asked by reflection on purpose: JCEF may be absent outright, and a *named* reference to `JBCefApp` throws
     * `NoClassDefFoundError` when the enclosing class is verified — before any `try` can catch it. No JCEF type may
     * be mentioned outside a class that loads only after this answers `true`.
     */
    val isAvailable: Boolean by lazy {
        val supported = runCatching {
            Class.forName("com.intellij.ui.jcef.JBCefApp", false, TestoReportViewer::class.java.classLoader)
                .getMethod("isSupported")
                .invoke(null) as Boolean
        }
        LOG.info("JCEF availability: ${supported.getOrNull() ?: "unavailable (${supported.exceptionOrNull()})"}")
        supported.getOrDefault(false)
    }

    /** `false` when there is no WebView to open, which is the caller's cue to fall back to the browser. */
    fun open(project: Project, reportPath: Path, label: String): Boolean {
        if (!isAvailable) return false
        val file = files.computeIfAbsent(reportPath.toString()) { TestoReportVirtualFile(reportPath, label) }
        val manager = FileEditorManager.getInstance(project)
        val wasOpen = manager.isFileOpen(file)
        manager.openFile(file, true)
        // The tab survives between runs, so the report it shows is the one loaded when it first opened.
        if (wasOpen) manager.getEditors(file).filterIsInstance<TestoReportFileEditor>().forEach { it.reload() }
        return true
    }

    private val LOG = logger<TestoReportViewer>()
}
