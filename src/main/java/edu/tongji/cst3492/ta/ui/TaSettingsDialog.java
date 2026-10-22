package edu.tongji.cst3492.ta.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.DocumentAdapter;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import edu.tongji.cst3492.ta.service.TaProjectService;
import edu.tongji.cst3492.ta.settings.TaSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;

/**
 * Dialog for configuring the LLM API endpoint, model name, session API key, and RAG top-K chunk count.
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
                "Built-in Offline Course TA (Deterministic Demo Mode)"
        });
        this.apiBaseUrlField = new JTextField(settings.apiBaseUrl, 32);
        this.apiKeyField = new JPasswordField(settings.resolveApiKey(), 32);
        this.modelNameField = new JTextField(settings.modelName, 24);
        this.topKSpinner = new JSpinner(new SpinnerNumberModel(
                Math.max(1, Math.min(8, settings.topKChunks)), 1, 8, 1
        ));

        // Reflect saved preset state on open
        if (settings.offlineDemoMode) {
            presetCombo.setSelectedIndex(2);
        } else if (settings.apiBaseUrl != null && settings.apiBaseUrl.contains("tongji.edu.cn")) {
            presetCombo.setSelectedIndex(1);
        } else {
            presetCombo.setSelectedIndex(0);
        }
        updateOnlineFieldsEnabled(!settings.offlineDemoMode);

        presetCombo.addActionListener(e -> {
            int idx = presetCombo.getSelectedIndex();
            if (idx == 0) {
                apiBaseUrlField.setText("https://api.deepseek.com/v1");
                modelNameField.setText("deepseek-chat");
            } else if (idx == 1) {
                apiBaseUrlField.setText("https://agi.tongji.edu.cn/v1");
                modelNameField.setText("DeepSeek-V4.1-Flash");
            }
            updateOnlineFieldsEnabled(idx != 2);
        });

        // Entering an API key while in offline mode switches to the online provider matching the base URL.
        apiKeyField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (presetCombo.getSelectedIndex() == 2 && !new String(apiKeyField.getPassword()).isBlank()) {
                    presetCombo.setSelectedIndex(apiBaseUrlField.getText().contains("tongji.edu.cn") ? 1 : 0);
                }
            }
        });

        init();
    }

    /** The API key field stays editable in offline mode so that typing a key can switch back online. */
    private void updateOnlineFieldsEnabled(boolean online) {
        apiBaseUrlField.setEnabled(online);
        modelNameField.setEnabled(online);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JLabel hintLabel = new JLabel(
                "<html><small>Security Note (Lecture 5 Slide 17): API Keys are stored in the IDE Password Safe "
                        + "(or read from <code>DEEPSEEK_API_KEY</code> / <code>TONGJI_API_KEY</code>) and never saved in plain-text XML.<br/>"
                        + "Online providers require an API key; choose <i>Built-in Offline Course TA</i> to work without one.</small></html>"
        );
        JPanel formPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Provider Mode:", presetCombo, 1, false)
                .addLabeledComponent("API Base URL:", apiBaseUrlField, 1, false)
                .addLabeledComponent("Model Name:", modelNameField, 1, false)
                .addLabeledComponent("Session API Key:", apiKeyField, 1, false)
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
        String apiKey = new String(apiKeyField.getPassword()).trim();
        // DeepSeek / Tongji presets are online mode (typing a key while offline already switched the preset).
        settings.offlineDemoMode = presetCombo.getSelectedIndex() == 2;
        settings.apiBaseUrl = apiBaseUrlField.getText().trim();
        settings.modelName = modelNameField.getText().trim();
        settings.setSessionApiKey(apiKey);
        settings.topKChunks = (Integer) topKSpinner.getValue();
        if (project != null) {
            TaProjectService.getInstance(project).refreshClientFromSettings();
        }
        super.doOKAction();
    }
}
