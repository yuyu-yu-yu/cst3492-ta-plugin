package edu.tongji.cst3492.ta.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.tongji.cst3492.ta.llm.EditorCodeContext;
import edu.tongji.cst3492.ta.llm.ModelClient;
import edu.tongji.cst3492.ta.llm.OfflineCourseTaClient;
import edu.tongji.cst3492.ta.llm.OpenAiCompatibleModelClient;
import edu.tongji.cst3492.ta.llm.TaResponse;
import edu.tongji.cst3492.ta.rag.CourseKnowledgeBase;
import edu.tongji.cst3492.ta.settings.TaSettingsState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Project-level IntelliJ service that manages the {@link CourseKnowledgeBase}, {@link TaChatSession},
 * and active {@link EditorCodeContext} for the CST3492 Course TA plugin.
 * Loads course PDFs asynchronously on a background thread so opening the Tool Window never blocks the EDT.
 */
@Service(Service.Level.PROJECT)
public final class TaProjectService {

    private final Project project;
    private final CourseKnowledgeBase knowledgeBase;
    private final TaChatSession chatSession;
    private final CountDownLatch initialIndexLatch = new CountDownLatch(1);
    private volatile EditorCodeContext pendingCodeContext = EditorCodeContext.empty();
    private volatile Consumer<EditorCodeContext> contextSelectionListener;
    private volatile Runnable indexUpdateListener;

    public TaProjectService(Project project) {
        this.project = project;
        this.knowledgeBase = new CourseKnowledgeBase();
        this.chatSession = new TaChatSession(knowledgeBase, createModelClientFromSettings());
        initializeKnowledgeBaseAsync();
    }

    private void initializeKnowledgeBaseAsync() {
        Runnable loadTask = () -> {
            try {
                knowledgeBase.loadBundledCourseMaterials();
                if (project != null && project.getBasePath() != null) {
                    knowledgeBase.scanDirectoryForCoursePdfs(Path.of(project.getBasePath()));
                }
                TaSettingsState settings = TaSettingsState.getInstance();
                if (settings.importedDocumentPaths != null) {
                    for (String pathStr : List.copyOf(settings.importedDocumentPaths)) {
                        try {
                            Path p = Path.of(pathStr);
                            if (Files.isRegularFile(p)) {
                                knowledgeBase.indexFile(p);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } finally {
                initialIndexLatch.countDown();
                Runnable listener = this.indexUpdateListener;
                if (listener != null) {
                    listener.run();
                }
            }
        };

        if (ApplicationManager.getApplication() != null) {
            ApplicationManager.getApplication().executeOnPooledThread(loadTask);
        } else {
            loadTask.run();
        }
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
        try {
            initialIndexLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    public void setIndexUpdateListener(Runnable listener) {
        this.indexUpdateListener = listener;
    }

    /**
     * Offline mode always uses the deterministic built-in TA. Online mode always uses the configured
     * OpenAI-compatible endpoint; a missing API key surfaces as an explicit error from the client
     * instead of silently degrading to offline answers.
     */
    private static ModelClient createModelClientFromSettings() {
        TaSettingsState settings = TaSettingsState.getInstance();
        if (settings.offlineDemoMode) {
            return new OfflineCourseTaClient();
        }
        return new OpenAiCompatibleModelClient(settings.apiBaseUrl, settings.resolveApiKey(), settings.modelName);
    }
}
