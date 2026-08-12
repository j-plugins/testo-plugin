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
 * An editor tab showing a generated Testo report in JCEF.
 *
 * A tab rather than a tool window so it can be split, kept alongside the code and closed like any other file. The
 * platform's own `HTMLEditorProvider` would have done this, but it is `@ApiStatus.Internal` — hence the light file plus
 * provider below, which is all public API.
 *
 * The report opens over `file://`, so it must not fetch its data: see the report spec in the Testo repository.
 */
class TestoReportVirtualFile(val reportPath: Path, label: String) : LightVirtualFile(label) {
    init {
        isWritable = false
    }

    val reportUrl: String get() = reportPath.toUri().toString()
}

class TestoReportFileEditor(private val file: TestoReportVirtualFile) : UserDataHolderBase(), FileEditor {

    // Null when the IDE runs without JCEF. The action checks TestoReportViewer.isAvailable before opening a tab, so
    // this is only the belt-and-braces path — and it is why the failure is swallowed rather than thrown at the editor.
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
     * Re-reads the report from disk — what a second click on the toolbar button means after a new run.
     *
     * Through `loadURL`, not `cefBrowser.reloadIgnoreCache()`: `CefBrowser` lives in `org.cef`, which the platform
     * artifact does not put on the compile classpath — it compiles only against a JDK that happens to bundle JCEF, and
     * CI's does not. `JBCefBrowser` is the module we do depend on, and navigating to the same `file://` URL re-reads it.
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
    // One light file per report path, so re-opening the same report returns to its tab instead of stacking up new ones
    // — FileEditorManager keys tabs by VirtualFile identity, not by equality.
    private val files = ConcurrentHashMap<String, TestoReportVirtualFile>()

    /**
     * Whether a report can be shown in a tab at all.
     *
     * Asked by reflection, and that is the point: JCEF may be absent outright (module not visible, unsupported
     * architecture, a remote-dev backend), and a *named* reference to `JBCefApp` in a method body throws
     * `NoClassDefFoundError` when that body's class is verified — before any `try` around the call can catch it. That is
     * what took the toolbar's whole action group down. Nothing else in this plugin mentions a JCEF type outside a class
     * that loads only once this has answered `true`.
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
