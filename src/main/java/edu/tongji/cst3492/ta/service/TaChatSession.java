package edu.tongji.cst3492.ta.service;

import edu.tongji.cst3492.ta.llm.ChatMessage;
import edu.tongji.cst3492.ta.llm.CitationVerifier;
import edu.tongji.cst3492.ta.llm.EditorCodeContext;
import edu.tongji.cst3492.ta.llm.ModelClient;
import edu.tongji.cst3492.ta.llm.OfflineCourseTaClient;
import edu.tongji.cst3492.ta.llm.TaPromptBuilder;
import edu.tongji.cst3492.ta.llm.TaResponse;
import edu.tongji.cst3492.ta.rag.CourseKnowledgeBase;
import edu.tongji.cst3492.ta.rag.ScoredChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the multi-turn RAG conversation session between the student and the Course TA.
 * Implements bounded history management ({@link #trimHistory()}) as taught in Lecture 5 (Slide 35-36):
 * - Keeps the system prompt at index 0
 * - Drops the oldest user/assistant exchange first when character count exceeds {@code maxHistoryChars}
 * - Verifies every response via {@link CitationVerifier}
 */
public final class TaChatSession {

    public static final int DEFAULT_MAX_HISTORY_CHARS = 6000;
    public static final int DEFAULT_TOP_K = 3;

    private final CourseKnowledgeBase knowledgeBase;
    private final List<ChatMessage> history = new ArrayList<>();
    private final int maxHistoryChars;
    private final OfflineCourseTaClient offlineFallback = new OfflineCourseTaClient();
    private volatile ModelClient primaryClient;
    private volatile int topK = DEFAULT_TOP_K;
    private int droppedMessagesCount = 0;

    public TaChatSession(CourseKnowledgeBase knowledgeBase, ModelClient primaryClient) {
        this(knowledgeBase, primaryClient, DEFAULT_MAX_HISTORY_CHARS);
    }

    public TaChatSession(CourseKnowledgeBase knowledgeBase, ModelClient primaryClient, int maxHistoryChars) {
        this.knowledgeBase = knowledgeBase;
        this.primaryClient = primaryClient != null ? primaryClient : offlineFallback;
        this.maxHistoryChars = Math.max(1000, maxHistoryChars);
        resetSession();
    }

    public synchronized void setPrimaryClient(ModelClient client) {
        this.primaryClient = client != null ? client : offlineFallback;
    }

    public synchronized void setTopK(int topK) {
        this.topK = Math.max(1, Math.min(8, topK));
    }

    public synchronized void resetSession() {
        history.clear();
        history.add(ChatMessage.system(TaPromptBuilder.SYSTEM_PROMPT));
        droppedMessagesCount = 0;
    }

    public synchronized TaResponse ask(String userQuestion, EditorCodeContext codeContext) {
        String retrievalQuery = buildRetrievalQuery(userQuestion, codeContext);
        List<ScoredChunk> retrievedChunks = knowledgeBase.retrieve(retrievalQuery, topK);

        String augmentedUserMessage = TaPromptBuilder.buildAugmentedUserMessage(
                userQuestion,
                codeContext,
                retrievedChunks
        );

        history.add(ChatMessage.user(augmentedUserMessage));
        trimHistory();

        String rawReply;
        String modelUsed;
        try {
            rawReply = primaryClient.complete(List.copyOf(history));
            modelUsed = primaryClient.providerName();
        } catch (Exception ex) {
            rawReply = offlineFallback.complete(List.copyOf(history));
            modelUsed = offlineFallback.providerName() + " [Fallback after error: " + ex.getMessage() + "]";
        }

        TaResponse verified = CitationVerifier.verifyAndFormat(rawReply, retrievedChunks, modelUsed);
        history.add(ChatMessage.assistant(verified.answer()));
        trimHistory();
        return verified;
    }

    void trimHistory() {
        while (totalHistoryChars() > maxHistoryChars && history.size() > 3) {
            // Keep index 0 (system prompt) and drop the oldest user+assistant exchange starting at index 1
            int nextUserIdx = findNextUserMessageIndex(2);
            if (nextUserIdx <= 1 || nextUserIdx >= history.size() - 1) {
                break;
            }
            droppedMessagesCount += (nextUserIdx - 1);
            history.subList(1, nextUserIdx).clear();
        }
    }

    private int findNextUserMessageIndex(int startFrom) {
        for (int i = startFrom; i < history.size(); i++) {
            if (ChatMessage.USER.equals(history.get(i).role())) {
                return i;
            }
        }
        return history.size();
    }

    public synchronized int totalHistoryChars() {
        int sum = 0;
        for (ChatMessage msg : history) {
            sum += msg.content().length();
        }
        return sum;
    }

    public synchronized List<ChatMessage> history() {
        return List.copyOf(history);
    }

    public synchronized int droppedMessagesCount() {
        return droppedMessagesCount;
    }

    private static String buildRetrievalQuery(String userQuestion, EditorCodeContext codeContext) {
        StringBuilder q = new StringBuilder();
        if (userQuestion != null) {
            q.append(userQuestion);
        }
        if (codeContext != null && codeContext.hasSelection()) {
            q.append(" ").append(codeContext.className())
                    .append(" ").append(codeContext.methodName())
                    .append(" ").append(codeContext.selectedCode());
        }
        return q.toString().trim();
    }
}
