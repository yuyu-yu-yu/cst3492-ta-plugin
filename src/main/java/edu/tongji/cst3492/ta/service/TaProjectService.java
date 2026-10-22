package edu.tongji.cst3492.ta.service;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.tongji.cst3492.ta.llm.EditorCodeContext;
import edu.tongji.cst3492.ta.llm.ModelClient;
import edu.tongji.cst3492.ta.llm.OfflineCourseTaClient;
import edu.tongji.cst3492.ta.llm.OpenAiCompatibleModelClient;
import edu.tongji.cst3492.ta.llm.TaResponse;
import edu.tongji.cst3492.ta.rag.CourseKnowledgeBase;
import edu.tongji.cst3492.ta.settings.TaSettingsState;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Project-level IntelliJ service that manages the {@link CourseKnowledgeBase}, {@link TaChatSession},
 * and active {@link EditorCodeContext} for the CST3492 Course TA plugin.
 */
@Service(Service.Level.PROJECT)
public final class TaProjectService {

    private final Project project;
    private final CourseKnowledgeBase knowledgeBase;
    private final TaChatSession chatSession;
    private volatile EditorCodeContext pendingCodeContext = EditorCodeContext.empty();
    private volatile Consumer<EditorCodeContext> contextSelectionListener;

    public TaProjectService(Project project) {
        this.project = project;
        this.knowledgeBase = new CourseKnowledgeBase();
        this.knowledgeBase.loadBundledCourseMaterials();
        if (project != null && project.getBasePath() != null) {
            this.knowledgeBase.scanDirectoryForCoursePdfs(Path.of(project.getBasePath()));
        }
        this.chatSession = new TaChatSession(knowledgeBase, createModelClientFromSettings());
    }

    public static TaProjectService getInstance(Project project) {
        return project.getService(TaProjectService.class);
    }

    public void refreshClientFromSettings() {
        TaSettingsState settings = TaSettingsState.getInstance();
        chatSession.setTopK(settings.topKChunks);
        chatSession.setPrimaryClient(createModelClientFromSettings());
    }

    public TaResponse askQuestion(String question, EditorCodeContext codeContext) {
        refreshClientFromSettings();
        return chatSession.ask(question, codeContext);
    }

    public Project project() {
        return project;
    }

    public CourseKnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    public TaChatSession chatSession() {
        return chatSession;
    }

    public void setPendingCodeContext(EditorCodeContext context) {
        this.pendingCodeContext = context == null ? EditorCodeContext.empty() : context;
        Consumer<EditorCodeContext> listener = this.contextSelectionListener;
        if (listener != null) {
            listener.accept(this.pendingCodeContext);
        }
    }

    public EditorCodeContext getPendingCodeContext() {
        return pendingCodeContext;
    }

    public void setContextSelectionListener(Consumer<EditorCodeContext> listener) {
        this.contextSelectionListener = listener;
    }

    private static ModelClient createModelClientFromSettings() {
        TaSettingsState settings = TaSettingsState.getInstance();
        String apiKey = settings.resolveApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            return new OpenAiCompatibleModelClient(settings.apiBaseUrl, apiKey, settings.modelName);
        }
        return new OfflineCourseTaClient();
    }
}
