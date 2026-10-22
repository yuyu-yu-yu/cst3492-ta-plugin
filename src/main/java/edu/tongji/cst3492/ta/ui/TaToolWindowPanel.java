package edu.tongji.cst3492.ta.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import edu.tongji.cst3492.ta.PluginConstants;
import edu.tongji.cst3492.ta.llm.EditorCodeContext;
import edu.tongji.cst3492.ta.llm.TaResponse;
import edu.tongji.cst3492.ta.rag.KnowledgeChunk;
import edu.tongji.cst3492.ta.service.TaProjectService;
import edu.tongji.cst3492.ta.settings.TaSettingsState;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Embedded IntelliJ IDEA Tool Window UI for interacting with the CST3492 Course TA.
 * Displays RAG-grounded answers with highlighted source document & page citations,
 * explicit general-knowledge notices, active code context banner, and course slide indexer controls.
 */
public final class TaToolWindowPanel extends JPanel {

    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("```(?:[a-zA-Z0-9_\\-]+)?\\R([\\s\\S]*?)```");

    private final Project project;
    private final TaProjectService taService;
    private final JEditorPane transcriptPane;
    private final StringBuilder transcriptHtml = new StringBuilder();
    private final JBTextArea questionInput;
    private final JLabel statusBannerLabel;
    private final JLabel codeContextBadge;
    private final JButton sendButton;
    private final JButton clearContextButton;
    private JButton ragBtn;
    private JButton toolBtn;
    private JButton generalBtn;
    /** True while a question is being answered; blocks overlapping submissions from any entry point. */
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    /** Incremented by "New Session" so responses of requests started before the reset are discarded. */
    private final AtomicLong uiSessionEpoch = new AtomicLong(0);

    public TaToolWindowPanel(Project project) {
        super(new BorderLayout(0, 6));
        this.project = project;
        this.taService = TaProjectService.getInstance(project);
        setBorder(JBUI.Borders.empty(8));

        // 1. Top Toolbar: Knowledge Base Status + Import PDF + Settings + Reset Chat
        JPanel topBar = new JPanel(new BorderLayout(6, 4));
        statusBannerLabel = new JLabel();
        updateKnowledgeBaseStatusLabel();

        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton importPdfBtn = new JButton("Import Slides");
        importPdfBtn.setToolTipText("Index additional course PDF slides or Markdown notes into RAG Knowledge Base");
        importPdfBtn.addActionListener(e -> importCourseMaterial());

        JButton settingsBtn = new JButton("LLM Settings");
        settingsBtn.addActionListener(e -> {
            TaSettingsDialog dialog = new TaSettingsDialog(project);
            if (dialog.showAndGet()) {
                updateKnowledgeBaseStatusLabel();
            }
        });

        JButton clearChatBtn = new JButton("New Session");
        clearChatBtn.addActionListener(e -> startNewSession());

        actionButtons.add(importPdfBtn);
        actionButtons.add(settingsBtn);
        actionButtons.add(clearChatBtn);

        topBar.add(statusBannerLabel, BorderLayout.NORTH);
        topBar.add(actionButtons, BorderLayout.SOUTH);
        add(topBar, BorderLayout.NORTH);

        // 2. Center Chat Transcript (HTML rendered cards with source badges)
        transcriptPane = new JEditorPane();
        transcriptPane.setEditable(false);
        transcriptPane.setEditorKit(HTMLEditorKitBuilder.simple());
        transcriptPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        JBScrollPane transcriptScroll = new JBScrollPane(transcriptPane);
        transcriptScroll.setBorder(BorderFactory.createLineBorder(JBColor.border()));
        add(transcriptScroll, BorderLayout.CENTER);

        // 3. Bottom Composer: Active Editor Code Context Banner + Question Input + Send Button
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 6));

        JPanel contextBar = new JPanel(new BorderLayout(6, 0));
        contextBar.setBorder(JBUI.Borders.empty(4, 6));
        codeContextBadge = new JLabel("Context: None (Select code in editor & right-click 'Ask CST3492 TA' or click 'Use Selection')");
        JPanel contextActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton captureEditorBtn = new JButton("Use Selection");
        captureEditorBtn.setToolTipText("Attach currently selected code in the active editor");
        captureEditorBtn.addActionListener(e -> captureActiveEditorSelection());

        clearContextButton = new JButton("Clear Context");
        clearContextButton.setEnabled(false);
        clearContextButton.addActionListener(e -> taService.setPendingCodeContext(EditorCodeContext.empty()));

        contextActions.add(captureEditorBtn);
        contextActions.add(clearContextButton);
        contextBar.add(codeContextBadge, BorderLayout.CENTER);
        contextBar.add(contextActions, BorderLayout.EAST);

        questionInput = new JBTextArea(3, 40);
        questionInput.setLineWrap(true);
        questionInput.setWrapStyleWord(true);
        questionInput.getEmptyText().setText("Ask a CST3492 course or Java coding question... (Ctrl+Enter / Cmd+Enter to send)");
        questionInput.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && (e.isControlDown() || e.isMetaDown())) {
                    e.consume();
                    submitCurrentQuestion();
                }
            }
        });

        sendButton = new JButton("Ask TA");
        sendButton.addActionListener(e -> submitCurrentQuestion());

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(new JBScrollPane(questionInput), BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);

        JPanel quickPromptsPanel = createQuickPromptsBar();

        JPanel composerContainer = new JPanel();
        composerContainer.setLayout(new BoxLayout(composerContainer, BoxLayout.Y_AXIS));
        composerContainer.add(contextBar);
        composerContainer.add(quickPromptsPanel);
        composerContainer.add(inputRow);

        bottomPanel.add(composerContainer, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        taService.setContextSelectionListener(ctx -> SwingUtilities.invokeLater(() -> {
            updateCodeContextBadge(ctx);
            questionInput.requestFocusInWindow();
        }));

        taService.setIndexUpdateListener(() -> SwingUtilities.invokeLater(() -> {
            updateKnowledgeBaseStatusLabel();
            if (taService.chatSession().history().size() <= 1) {
                resetTranscriptWithWelcome();
            }
        }));

        resetTranscriptWithWelcome();
    }

    private JPanel createQuickPromptsBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        bar.add(new JLabel("Quick Demo:"));

        ragBtn = new JButton("4 Steps of RAG");
        ragBtn.addActionListener(e -> {
            questionInput.setText("What are the four steps of RAG in Lecture 5 and how does Reciprocal Rank Fusion merge rankings?");
            submitCurrentQuestion();
        });

        toolBtn = new JButton("Tools & Bounded Loop");
        toolBtn.addActionListener(e -> {
            questionInput.setText("How does ToolDispatcher validate tool calls and why does the loop need a STEP_LIMIT in Lecture 5?");
            submitCurrentQuestion();
        });

        generalBtn = new JButton("General Knowledge");
        generalBtn.addActionListener(e -> {
            questionInput.setText("How does Spring Boot @Autowired dependency injection work?");
            submitCurrentQuestion();
        });

        bar.add(ragBtn);
        bar.add(toolBtn);
        bar.add(generalBtn);
        return bar;
    }

    public boolean isRequestInFlight() {
        return requestInFlight.get();
    }

    public void startNewSession() {
        uiSessionEpoch.incrementAndGet();
        taService.chatSession().resetSession();
        requestInFlight.set(false);
        setRequestControlsEnabled(true);
        resetTranscriptWithWelcome();
    }

    /** Toggles the send button and Quick Demo buttons; must be called on the EDT. */
    private void setRequestControlsEnabled(boolean enabled) {
        sendButton.setText(enabled ? "Ask TA" : "Thinking...");
        sendButton.setEnabled(enabled);
        ragBtn.setEnabled(enabled);
        toolBtn.setEnabled(enabled);
        generalBtn.setEnabled(enabled);
    }

    public void prefillQuestionWithContext(EditorCodeContext context, String suggestedQuestion) {
        prefillQuestionWithContext(context, suggestedQuestion, null);
    }

    public void prefillQuestionWithContext(EditorCodeContext context, String suggestedQuestion, Runnable onComplete) {
        taService.setPendingCodeContext(context);
        if (suggestedQuestion != null && !suggestedQuestion.isBlank()) {
            questionInput.setText(suggestedQuestion);
            submitCurrentQuestion(onComplete);
        } else {
            if (questionInput.getText().isBlank()) {
                questionInput.setText("Please explain this selected code, check for potential issues, and relate it to CST3492 course concepts.");
            }
            questionInput.requestFocusInWindow();
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    private void submitCurrentQuestion() {
        submitCurrentQuestion(null);
    }

    private void submitCurrentQuestion(Runnable onComplete) {
        String question = questionInput.getText().trim();
        if (question.isEmpty()) {
            return;
        }
        if (!requestInFlight.compareAndSet(false, true)) {
            return;
        }
        long epochAtSubmit = uiSessionEpoch.get();
        EditorCodeContext activeCtx = taService.getPendingCodeContext();
        questionInput.setText("");
        setRequestControlsEnabled(false);

        appendUserMessageHtml(question, activeCtx);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                TaResponse response = taService.askQuestion(question, activeCtx);
                SwingUtilities.invokeLater(() -> {
                    if (uiSessionEpoch.get() != epochAtSubmit) {
                        return;
                    }
                    appendTaResponseHtml(response);
                    updateKnowledgeBaseStatusLabel();
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    if (uiSessionEpoch.get() != epochAtSubmit) {
                        return;
                    }
                    appendErrorHtml(t.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    if (uiSessionEpoch.get() != epochAtSubmit) {
                        return;
                    }
                    requestInFlight.set(false);
                    setRequestControlsEnabled(true);
                    questionInput.requestFocusInWindow();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        });
    }

    private void captureActiveEditorSelection() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            Messages.showInfoMessage(project, "No active editor tab is open.", PluginConstants.PLUGIN_NAME);
            return;
        }
        EditorCodeContext ctx = extractEditorContext(project, editor);
        if (!ctx.hasSelection()) {
            Messages.showInfoMessage(project, "Please select a code segment in the editor first.", PluginConstants.PLUGIN_NAME);
            return;
        }
        taService.setPendingCodeContext(ctx);
    }

    public static EditorCodeContext extractEditorContext(Project project, Editor editor) {
        if (editor == null) {
            return EditorCodeContext.empty();
        }
        SelectionModel selectionModel = editor.getSelectionModel();
        String selectedText = selectionModel.getSelectedText();
        if (selectedText == null || selectedText.isBlank()) {
            return EditorCodeContext.empty();
        }

        Document document = editor.getDocument();
        VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
        String fileName = vf != null ? vf.getName() : "Selection.java";
        int startLine = document.getLineNumber(selectionModel.getSelectionStart()) + 1;
        int endLine = document.getLineNumber(selectionModel.getSelectionEnd()) + 1;

        String className = "";
        String methodName = "";
        if (project != null) {
            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
            if (psiFile != null) {
                PsiElement elementAtStart = psiFile.findElementAt(selectionModel.getSelectionStart());
                if (elementAtStart != null) {
                    PsiClass psiClass = PsiTreeUtil.getParentOfType(elementAtStart, PsiClass.class);
                    PsiMethod psiMethod = PsiTreeUtil.getParentOfType(elementAtStart, PsiMethod.class);
                    if (psiClass != null && psiClass.getName() != null) {
                        className = psiClass.getName();
                    }
                    if (psiMethod != null && psiMethod.getName() != null) {
                        methodName = psiMethod.getName();
                    }
                }
            }
        }
        return new EditorCodeContext(fileName, className, methodName, startLine, endLine, selectedText);
    }

    private void importCourseMaterial() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Select Course Material (PDF / Markdown / TXT)")
                .withDescription("Choose lecture slides or course notes to index into the CST3492 RAG Knowledge Base")
                .withFileFilter(file -> {
                    String ext = file.getExtension();
                    return ext != null && (ext.equalsIgnoreCase("pdf")
                            || ext.equalsIgnoreCase("md")
                            || ext.equalsIgnoreCase("txt"));
                });

        VirtualFile[] files = FileChooser.chooseFiles(descriptor, project, null);
        if (files.length == 0) {
            return;
        }

        List<Path> selectedPaths = new ArrayList<>();
        for (VirtualFile vf : files) {
            selectedPaths.add(Path.of(vf.getPath()));
        }

        statusBannerLabel.setText("<html><b>RAG Index:</b> Indexing " + selectedPaths.size() + " file(s)...</html>");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            int totalNewChunks = 0;
            List<String> errors = new ArrayList<>();
            TaSettingsState settings = TaSettingsState.getInstance();

            for (Path path : selectedPaths) {
                try {
                    List<KnowledgeChunk> indexed = taService.knowledgeBase().indexFile(path);
                    totalNewChunks += indexed.size();
                    String abs = path.toAbsolutePath().toString();
                    if (!settings.importedDocumentPaths.contains(abs)) {
                        settings.importedDocumentPaths.add(abs);
                    }
                } catch (Exception ex) {
                    errors.add(path.getFileName() + ": " + ex.getMessage());
                }
            }

            int finalTotalNewChunks = totalNewChunks;
            SwingUtilities.invokeLater(() -> {
                updateKnowledgeBaseStatusLabel();
                if (!errors.isEmpty()) {
                    Messages.showErrorDialog(project, "Failed to parse:\n" + String.join("\n", errors), "Import Error");
                } else {
                    Messages.showInfoMessage(
                            project,
                            "Indexed " + selectedPaths.size() + " course file(s) (" + finalTotalNewChunks + " searchable knowledge chunks).",
                            PluginConstants.PLUGIN_NAME
                    );
                }
            });
        });
    }

    private void updateKnowledgeBaseStatusLabel() {
        Map<String, Integer> docs = taService.knowledgeBase().documentSummary();
        int totalChunks = taService.knowledgeBase().totalChunks();
        String docList = docs.isEmpty()
                ? "Indexing..."
                : docs.entrySet().stream()
                .map(e -> e.getKey() + " (" + e.getValue() + " chunks)")
                .collect(Collectors.joining(", "));
        statusBannerLabel.setText("<html><b>RAG Index:</b> " + totalChunks + " chunks across " + docs.size() + " course doc(s)</html>");
        statusBannerLabel.setToolTipText("Indexed Documents: " + docList);
    }

    private void updateCodeContextBadge(EditorCodeContext ctx) {
        if (ctx != null && ctx.hasSelection()) {
            codeContextBadge.setText("<html><b>Active Code Context:</b> <code>" + escapeHtml(ctx.formatSummary()) + "</code></html>");
            clearContextButton.setEnabled(true);
        } else {
            codeContextBadge.setText("Context: None (Select code in editor & right-click 'Ask CST3492 TA' or click 'Use Selection')");
            clearContextButton.setEnabled(false);
        }
    }

    private void resetTranscriptWithWelcome() {
        transcriptHtml.setLength(0);
        int chunks = taService.knowledgeBase().totalChunks();
        int docs = taService.knowledgeBase().documentSummary().size();
        transcriptHtml.append("<div style='font-family:sans-serif; padding:8px;'>")
                .append("<div style='padding:10px; border:1px solid #4A90E2; border-radius:6px; margin-bottom:10px;'>")
                .append("<b>CST3492 Course Teaching Assistant (RAG + Source Citation)</b><br/>")
                .append("<small>Indexed Knowledge Base: <b>").append(chunks).append(" chunks</b> from <b>").append(docs).append(" course document(s)</b>.</small><br/>")
                .append("<ul>")
                .append("<li>Ask any question about <i>Java Enterprise Application Development</i> (RAG, BM25, Vector Search, RRF, LLM Tools, Concurrency, JUnit).</li>")
                .append("<li>Every course-backed answer cites exact <code>[Source: Document, Page N]</code>.</li>")
                .append("<li>If no course material matches, the TA explicitly states: <i>\"").append(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK).append("\"</i></li>")
                .append("<li>Select code in the editor and right-click <b>Ask CST3492 TA About Selected Code</b> for context-aware help.</li>")
                .append("</ul></div></div>");
        renderTranscript();
    }

    private int lastUserMessageDocPos = 0;

    private void appendUserMessageHtml(String question, EditorCodeContext ctx) {
        lastUserMessageDocPos = transcriptPane.getDocument().getLength();
        transcriptHtml.append("<div style='font-family:sans-serif; margin:8px; padding:8px; border-left:3px solid #28A745;'>")
                .append("<b>You:</b><br/>");
        if (ctx != null && ctx.hasSelection()) {
            transcriptHtml.append("<div style='margin:4px 0; padding:4px; font-family:monospace; font-size:11px;'>")
                    .append("<b>[Attached Code Context: ").append(escapeHtml(ctx.formatSummary())).append("]</b><br/>")
                    .append("<pre style='margin:2px 0;'>").append(escapeHtml(truncateCode(ctx.selectedCode(), 220))).append("</pre>")
                    .append("</div>");
        }
        transcriptHtml.append(formatMarkdownLikeHtml(question))
                .append("</div>");
        renderTranscriptAt(lastUserMessageDocPos);
    }

    private void appendTaResponseHtml(TaResponse response) {
        int scrollPosBefore = lastUserMessageDocPos;
        String badgeColor = response.basedOnCourseMaterial() ? "#2EA043" : "#D29922";
        String badgeBg = response.basedOnCourseMaterial() ? "#1B3324" : "#382A14";
        String badgeTitle = response.basedOnCourseMaterial()
                ? "Verified Course Material Citations:<br/><code>" + escapeHtml(response.citationSummary()) + "</code>"
                : escapeHtml(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK);

        transcriptHtml.append("<div style='font-family:sans-serif; margin:8px; padding:10px; border:1px solid #555555;'>")
                .append("<b>CST3492 TA</b> <small>(").append(escapeHtml(response.modelUsed())).append(")</small><br/>")
                .append("<div style='margin:6px 0; padding:6px 8px; background-color:").append(badgeBg)
                .append("; border-left:4px solid ").append(badgeColor).append(";'>")
                .append("<b>Source Verification:</b> ").append(badgeTitle)
                .append("</div>")
                .append("<div style='margin-top:6px;'>")
                .append(formatMarkdownLikeHtml(response.answer()))
                .append("</div>")
                .append("</div>");
        transcriptPane.setText("<html><body>" + transcriptHtml + "</body></html>");
        transcriptPane.setCaretPosition(transcriptPane.getDocument().getLength());
    }

    private void appendErrorHtml(String message) {
        int scrollPosBefore = transcriptPane.getDocument().getLength();
        transcriptHtml.append("<div style='font-family:sans-serif; margin:8px; padding:10px; border:1px solid #C93B2B;'>")
                .append("<b>CST3492 TA Error:</b> ").append(escapeHtml(message))
                .append("</div>");
        renderTranscriptAt(scrollPosBefore);
    }

    private void renderTranscript() {
        transcriptPane.setText("<html><body>" + transcriptHtml + "</body></html>");
        transcriptPane.setCaretPosition(0);
    }

    private void renderTranscriptAt(int targetPos) {
        transcriptPane.setText("<html><body>" + transcriptHtml + "</body></html>");
        int docLen = transcriptPane.getDocument().getLength();
        transcriptPane.setCaretPosition(Math.max(0, Math.min(targetPos, docLen)));
    }

    public static String formatMarkdownLikeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Extract fenced code blocks first
        List<String> codeBlocks = new ArrayList<>();
        Matcher fenceMatcher = CODE_FENCE_PATTERN.matcher(text);
        StringBuilder withPlaceholders = new StringBuilder();
        while (fenceMatcher.find()) {
            String codeContent = fenceMatcher.group(1);
            int idx = codeBlocks.size();
            codeBlocks.add("<pre style='padding:6px; border:1px solid #666666; font-family:monospace;'>"
                    + escapeHtml(codeContent.trim()) + "</pre>");
            fenceMatcher.appendReplacement(withPlaceholders, "@@CODE_BLOCK_" + idx + "@@");
        }
        fenceMatcher.appendTail(withPlaceholders);

        String[] lines = withPlaceholders.toString().split("\\R");
        StringBuilder html = new StringBuilder();
        String openList = null; // "ul", "ol", or null when no list is open

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            String listTag = null;
            String itemText = null;
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                listTag = "ul";
                itemText = trimmed.substring(2);
            } else if (trimmed.matches("^\\d+\\.\\s+.*")) {
                listTag = "ol";
                itemText = trimmed.substring(trimmed.indexOf('.') + 1).trim();
            }
            if (openList != null && !openList.equals(listTag)) {
                html.append("</").append(openList).append(">");
                openList = null;
            }
            if (listTag != null) {
                if (openList == null) {
                    html.append("<").append(listTag).append(" style='margin:4px 0 4px 18px;'>");
                    openList = listTag;
                }
                html.append("<li>").append(formatInlineMarkdown(itemText)).append("</li>");
            } else if (trimmed.equals("---")) {
                html.append("<hr/>");
            } else if (trimmed.startsWith("### ")) {
                html.append("<div style='font-size:13px; font-weight:bold; margin:6px 0 3px 0;'>")
                        .append(formatInlineMarkdown(trimmed.substring(4)))
                        .append("</div>");
            } else if (trimmed.startsWith("## ")) {
                html.append("<div style='font-size:14px; font-weight:bold; margin:8px 0 4px 0;'>")
                        .append(formatInlineMarkdown(trimmed.substring(3)))
                        .append("</div>");
            } else if (trimmed.isEmpty()) {
                html.append("<br/>");
            } else {
                html.append(formatInlineMarkdown(rawLine)).append("<br/>");
            }
        }
        if (openList != null) {
            html.append("</").append(openList).append(">");
        }

        String result = html.toString();
        for (int i = 0; i < codeBlocks.size(); i++) {
            result = result.replace("@@CODE_BLOCK_" + i + "@@", codeBlocks.get(i));
        }
        return result;
    }

    private static String formatInlineMarkdown(String line) {
        String escaped = escapeHtml(line);
        escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
        return escaped;
    }

    private static String truncateCode(String code, int maxLen) {
        if (code == null || code.length() <= maxLen) {
            return code == null ? "" : code;
        }
        return code.substring(0, maxLen) + "\n... [truncated]";
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
