package edu.tongji.cst3492.ta.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Registers the "CST3492 TA" sidebar Tool Window in IntelliJ IDEA.
 */
public final class TaToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final String TOOL_WINDOW_ID = "CST3492 TA";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        TaToolWindowPanel panel = new TaToolWindowPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
