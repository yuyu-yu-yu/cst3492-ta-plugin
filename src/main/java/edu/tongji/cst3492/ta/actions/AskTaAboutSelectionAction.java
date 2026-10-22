package edu.tongji.cst3492.ta.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import edu.tongji.cst3492.ta.PluginConstants;
import edu.tongji.cst3492.ta.llm.EditorCodeContext;
import edu.tongji.cst3492.ta.service.TaProjectService;
import edu.tongji.cst3492.ta.ui.TaToolWindowFactory;
import edu.tongji.cst3492.ta.ui.TaToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/**
 * Right-click editor context menu action allowing students to select a Java code snippet
 * and immediately ask the CST3492 Course TA a question grounded in both their code context
 * and the indexed course materials.
 */
public final class AskTaAboutSelectionAction extends AnAction {

    private static final String BUSY_MESSAGE =
            "CST3492 TA is currently answering a previous question. Please wait or start a New Session.";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean hasSelection = project != null && editor != null && editor.getSelectionModel().hasSelection();
        e.getPresentation().setEnabledAndVisible(hasSelection);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }

        EditorCodeContext context = TaToolWindowPanel.extractEditorContext(project, editor);
        if (!context.hasSelection()) {
            Messages.showInfoMessage(project, "Please select a code segment in the editor first.", PluginConstants.PLUGIN_NAME);
            return;
        }

        TaToolWindowPanel openPanel = findPanel(project);
        if (openPanel != null && openPanel.isRequestInFlight()) {
            Messages.showInfoMessage(project, BUSY_MESSAGE, PluginConstants.PLUGIN_NAME);
            return;
        }

        String userQuestion = Messages.showInputDialog(
                project,
                "Selected Context: " + context.formatSummary() + "\n\nEnter your question for the CST3492 Course TA:",
                "Ask CST3492 TA About Selected Code",
                Messages.getQuestionIcon(),
                "Explain how this code works, identify any issues, and connect it to our CST3492 lecture materials.",
                null
        );

        if (userQuestion == null) {
            // User cancelled dialog, still attach context to tool window
            TaProjectService.getInstance(project).setPendingCodeContext(context);
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TaToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(() -> {
                Content content = toolWindow.getContentManager().getContent(0);
                if (content != null) {
                    JComponent component = content.getComponent();
                    if (component instanceof TaToolWindowPanel panel) {
                        if (panel.isRequestInFlight()) {
                            Messages.showInfoMessage(project, BUSY_MESSAGE, PluginConstants.PLUGIN_NAME);
                            return;
                        }
                        panel.prefillQuestionWithContext(context, userQuestion);
                        return;
                    }
                }
                TaProjectService.getInstance(project).setPendingCodeContext(context);
            });
        } else {
            TaProjectService.getInstance(project).setPendingCodeContext(context);
        }
    }

    private static TaToolWindowPanel findPanel(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(TaToolWindowFactory.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return null;
        }
        Content content = toolWindow.getContentManager().getContent(0);
        if (content != null && content.getComponent() instanceof TaToolWindowPanel panel) {
            return panel;
        }
        return null;
    }
}
