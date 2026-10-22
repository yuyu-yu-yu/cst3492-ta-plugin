package edu.tongji.cst3492.ta.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import edu.tongji.cst3492.ta.service.TaProjectService;
import edu.tongji.cst3492.ta.settings.TaSettingsState;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;

/**
 * Dialog for configuring the LLM API endpoint, model name, API key, and RAG top-K chunk count.
 */
public final class TaSettingsDialog extends DialogWrapper {

    private final Project project;
    private final JComboBox<String> presetCombo;
    private final JTextField apiBaseUrlField;
    private final JPasswordField apiKeyField;
    private final JTextField modelNameField;
    private final JSpinner topKSpinner;

    public TaSettingsDialog(@Nullable Project project) {
        super(project);
        this.project = project;
        setTitle("CST3492 Course TA - LLM & RAG Settings");

        TaSettingsState settings = TaSettingsState.getInstance();

        this.presetCombo = new JComboBox<>(new String[]{
                "DeepSeek API (https://api.deepseek.com/v1)",
                "Tongji AI Platform (https://agi.tongji.edu.cn/v1)",
                "Built-in Offline Course TA (No API Key Required)"
        });
        this.apiBaseUrlField = new JTextField(settings.apiBaseUrl, 32);
        this.apiKeyField = new JPasswordField(settings.apiKey, 32);
        this.modelNameField = new JTextField(settings.modelName, 24);
        this.topKSpinner = new JSpinner(new SpinnerNumberModel(
                Math.max(1, Math.min(8, settings.topKChunks)), 1, 8, 1
        ));

        presetCombo.addActionListener(e -> {
            int idx = presetCombo.getSelectedIndex();
            if (idx == 0) {
                apiBaseUrlField.setText("https://api.deepseek.com/v1");
                modelNameField.setText("deepseek-chat");
            } else if (idx == 1) {
                apiBaseUrlField.setText("https://agi.tongji.edu.cn/v1");
                modelNameField.setText("DeepSeek-V3");
            } else if (idx == 2) {
                apiKeyField.setText("");
            }
        });

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JLabel hintLabel = new JLabel(
                "<html><small>Tip: Leave API Key blank to use environment variable <code>DEEPSEEK_API_KEY</code> "
                        + "or the built-in Offline Course TA Engine.</small></html>"
        );
        JPanel formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Provider Preset:", presetCombo, 1, false)
                .addLabeledComponent("API Base URL:", apiBaseUrlField, 1, false)
                .addLabeledComponent("Model Name:", modelNameField, 1, false)
                .addLabeledComponent("API Key:", apiKeyField, 1, false)
                .addLabeledComponent("RAG Top-K Chunks:", topKSpinner, 1, false)
                .addComponentFillVertically(hintLabel, 8)
                .getPanel();
        formPanel.setBorder(JBUI.Borders.empty(8));
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(formPanel, BorderLayout.CENTER);
        return wrapper;
    }

    @Override
    protected void doOKAction() {
        TaSettingsState settings = TaSettingsState.getInstance();
        settings.apiBaseUrl = apiBaseUrlField.getText().trim();
        settings.modelName = modelNameField.getText().trim();
        settings.apiKey = new String(apiKeyField.getPassword()).trim();
        settings.topKChunks = (Integer) topKSpinner.getValue();
        if (project != null) {
            TaProjectService.getInstance(project).refreshClientFromSettings();
        }
        super.doOKAction();
    }
}
