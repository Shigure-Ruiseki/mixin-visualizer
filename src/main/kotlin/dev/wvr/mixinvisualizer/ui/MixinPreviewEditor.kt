package dev.wvr.mixinvisualizer.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.util.Alarm
import dev.wvr.mixinvisualizer.lang.BytecodeFileType
import dev.wvr.mixinvisualizer.logic.MixinCompilationTopic
import dev.wvr.mixinvisualizer.logic.MixinProcessor
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class MixinPreviewEditor(
    private val project: Project,
    private val file: VirtualFile
) : FileEditor {
    private val panel = JPanel(BorderLayout())
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this)
    private val diffPanel = DiffManager.getInstance().createRequestPanel(project, this, null)

    private val processor = MixinProcessor(project)

    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var isVisible = false
    private var isDirty = true

    private var showBytecode = false

    private var pendingScrollTarget: String? = null
    private var currentResultDocument: com.intellij.openapi.editor.Document? = null

    init {
        loadingPanel.add(diffPanel.component, BorderLayout.CENTER)
        panel.add(createToolbar(), BorderLayout.NORTH)
        panel.add(loadingPanel, BorderLayout.CENTER)

        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) {
                if (event.file?.virtualFile == file) {
                    scheduleRefresh()
                }
            }
        }, this)

        val connection = project.messageBus.connect(this)
        connection.subscribe(MixinCompilationTopic.TOPIC, object : MixinCompilationTopic {
            override fun onCompilationFinished() {
                scheduleRefresh(immediate = true)
            }
        })

        scheduleRefresh(immediate = true)
    }

    private fun scheduleRefresh(immediate: Boolean = false) {
        if (project.isDisposed) return

        isDirty = true
        updateAlarm.cancelAllRequests()

        val delay = if (immediate) 0 else 500

        updateAlarm.addRequest({
            if (isVisible && !project.isDisposed) {
                refresh()
            }
        }, delay, ModalityState.defaultModalityState())
    }

    override fun selectNotify() {
        isVisible = true
        if (isDirty) {
            scheduleRefresh(immediate = true)
        }
    }

    override fun deselectNotify() {
        isVisible = false
    }

    fun scrollToMethod(methodName: String) {
        this.pendingScrollTarget = methodName
        performScroll()
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()

        val refreshAction = object : AnAction("Refresh", "Reload Mixin changes", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                scheduleRefresh(immediate = true)
            }
        }

        val toggleAction = object :
            ToggleAction("Show Bytecode", "Toggle between Java and ASM Bytecode view", AllIcons.FileTypes.JavaClass) {
            override fun isSelected(e: AnActionEvent) = showBytecode
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showBytecode = state
                scheduleRefresh(immediate = true)
            }
        }

        group.add(refreshAction)
        group.add(toggleAction)

        val toolbar = ActionManager.getInstance().createActionToolbar("MixinVisualizerToolbar", group, true)
        toolbar.targetComponent = diffPanel.component
        return toolbar.component
    }

    private fun refresh() {
        if (project.isDisposed || !file.isValid) return
        val psi = PsiManager.getInstance(project).findFile(file) ?: return

        isDirty = false
        loadingPanel.startLoading()

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread

            val (orig, trans) = DumbService.getInstance(project).runReadActionInSmartMode<Pair<String, String>> {
                processor.process(psi, showBytecode)
            }

            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    updateDiff(orig, trans)
                    loadingPanel.stopLoading()
                }
            }, ModalityState.defaultModalityState())
        }
    }

    private fun updateDiff(original: String, transformed: String) {
        if (Disposer.isDisposed(diffPanel)) return

        val factory = DiffContentFactory.getInstance()
        val fileType = if (showBytecode) BytecodeFileType else JavaFileType.INSTANCE

        val content1 = factory.create(project, original, fileType)
        val content2 = factory.create(project, transformed, fileType)

        this.currentResultDocument = content2.document

        val request = SimpleDiffRequest("Mixin Diff", content1, content2, "Target (Original)", "Target (Injected)")
        diffPanel.setRequest(request)

        performScroll()
    }

    private fun performScroll() {
        val targetName = pendingScrollTarget ?: return
        val doc = currentResultDocument ?: return

        val text = doc.charsSequence
        var idx = text.indexOf(" $targetName(")
        if (idx == -1) idx = text.indexOf(" $targetName ")
        if (idx == -1) idx = text.indexOf(targetName)

        if (idx != -1) {
            val editors = EditorFactory.getInstance().getEditors(doc, project)
            for (editor in editors) {
                if (!editor.isDisposed) {
                    val offset = idx
                    val line = doc.getLineNumber(offset)

                    editor.caretModel.moveToOffset(offset)
                    editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)

                    editor.selectionModel.setSelection(offset, offset + targetName.length)
                }
            }
        }

        pendingScrollTarget = null
    }

    override fun getFile() = file
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent() = diffPanel.preferredFocusedComponent
    override fun getName() = "Mixin Preview"
    override fun setState(s: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(l: PropertyChangeListener) {}
    override fun removePropertyChangeListener(l: PropertyChangeListener) {}
    override fun getCurrentLocation() = null
    override fun getBackgroundHighlighter() = null
    override fun dispose() {
        Disposer.dispose(diffPanel)
    }

    override fun <T> getUserData(key: Key<T>) = null
    override fun <T> putUserData(key: Key<T>, v: T?) {}
}