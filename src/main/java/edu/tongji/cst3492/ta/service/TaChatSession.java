package edu.tongji.cst3492.ta.service;

import edu.tongji.cst3492.ta.PluginConstants;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates the multi-turn RAG conversation session between the student and the Course TA.
 * Fixes multi-turn memory and EDT concurrency:
 * - Stores ONLY compact user questions (and code context header) in {@code history}; retrieved RAG chunks
 *   are injected into the ephemeral request payload for the current turn only so multi-turn memory is preserved.
 * - Executes {@link ModelClient#complete(List)} OUTSIDE the {@code synchronized} lock and uses a session
 *   generation counter so clicking "New Session" on the EDT never blocks and discards stale in-flight replies.
 * - Surfaces online LLM errors explicitly instead of silently masking them with offline template text.
 */
public final class TaChatSession {

    public static final int DEFAULT_MAX_HISTORY_CHARS = 4000;
    public static final int DEFAULT_TOP_K = 3;

    private static final Pattern FOLLOW_UP_MARKER_PATTERN = Pattern.compile(
            "\\b(?:it|this|that|these|those|they|them|its|their|more|detail|details|elaborate|further|again|previous|above|why|example|examples)\\b"
                    + "|(?:展开|详细|继续|上面|刚才|为什么|它|这个|那个)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern STANDALONE_TECH_NOUN_PATTERN = Pattern.compile(
            "\\b(?:jpa|hibernate|spring|maven|gradle|docker|kubernetes|redis|kafka|mysql|postgres|sql|hashmap|treemap|arraylist|linkedlist|rag|bm25|rrf|llm|json|junit|servlet|jdbc|jvm|gc|thread|mutex|semaphore|deadlock|microservice|rest|graphql|tcp|http|https|oauth|jwt)\\b"
                    + "|[A-Z][a-z]+[A-Z][A-Za-z0-9]*",
            Pattern.CASE_INSENSITIVE
    );

    private final Map<String, ScoredChunk> sessionSuppliedChunks = new LinkedHashMap<>();
    private final CourseKnowledgeBase knowledgeBase;
    private final List<ChatMessage> history = new ArrayList<>();
    private final int maxHistoryChars;
    private final AtomicLong sessionGeneration = new AtomicLong(0);
    private volatile ModelClient primaryClient;
    private volatile int topK = DEFAULT_TOP_K;
    private int droppedMessagesCount = 0;

    public TaChatSession(CourseKnowledgeBase knowledgeBase, ModelClient primaryClient) {
        this(knowledgeBase, primaryClient, DEFAULT_MAX_HISTORY_CHARS);
    }

    public TaChatSession(CourseKnowledgeBase knowledgeBase, ModelClient primaryClient, int maxHistoryChars) {
        this.knowledgeBase = knowledgeBase;
        this.primaryClient = primaryClient != null ? primaryClient : new OfflineCourseTaClient();
        this.maxHistoryChars = Math.max(600, maxHistoryChars);
        resetSession();
    }

    public void setPrimaryClient(ModelClient client) {
        this.primaryClient = client != null ? client : new OfflineCourseTaClient();
    }

    public void setTopK(int topK) {
        this.topK = Math.max(1, Math.min(8, topK));
    }

    public synchronized void resetSession() {
        sessionGeneration.incrementAndGet();
        history.clear();
        history.add(ChatMessage.system(TaPromptBuilder.SYSTEM_PROMPT));
        sessionSuppliedChunks.clear();
        droppedMessagesCount = 0;
    }

    public long currentGeneration() {
        return sessionGeneration.get();
    }

    public TaResponse ask(String userQuestion, EditorCodeContext codeContext) {
        int currentTopK = this.topK;
        ModelClient client = this.primaryClient;

        List<ChatMessage> requestMessages;
        String retrievalQuery;
        long genAtStart;

        synchronized (this) {
            genAtStart = sessionGeneration.get();
            retrievalQuery = buildRetrievalQuery(userQuestion, codeContext);
        }

        List<ScoredChunk> retrievedChunks = knowledgeBase.retrieve(retrievalQuery, currentTopK);
        String augmentedCurrentTurnMessage = TaPromptBuilder.buildAugmentedUserMessage(
                userQuestion,
                codeContext,
                retrievedChunks
        );

        List<ScoredChunk> verificationChunks = new ArrayList<>(retrievedChunks);
        synchronized (this) {
            for (ScoredChunk sc : sessionSuppliedChunks.values()) {
                boolean alreadyInCurrent = retrievedChunks.stream()
                        .anyMatch(curr -> curr.chunk().id().equals(sc.chunk().id()));
                if (!alreadyInCurrent) {
                    verificationChunks.add(sc);
                }
            }
            for (ScoredChunk sc : retrievedChunks) {
                sessionSuppliedChunks.put(sc.chunk().id(), sc);
            }
            requestMessages = new ArrayList<>(history);
            requestMessages.add(ChatMessage.user(augmentedCurrentTurnMessage));
        }
        // Execute LLM call OUTSIDE the synchronized lock so resetSession() on EDT never blocks
        String rawReply;
        String modelUsed = client.providerName();
        try {
            rawReply = client.complete(List.copyOf(requestMessages));
        } catch (Exception ex) {
            String errorMsg = "**LLM Service Request Failed (`" + modelUsed + "`)**\n\n"
                    + "- **Error:** `" + ex.getMessage() + "`\n"
                    + "- **Action:** Please check your API Key and Base URL in **LLM Settings**, or switch the provider preset to **Built-in Offline Course TA** for offline demonstration.\n\n---\n"
                    + "Note: " + PluginConstants.GENERAL_KNOWLEDGE_FALLBACK;
            return new TaResponse(
                    errorMsg,
                    List.of(),
                    false,
                    PluginConstants.GENERAL_KNOWLEDGE_FALLBACK,
                    modelUsed + " (Error)"
            );
        }

        TaResponse verified = CitationVerifier.verifyAndFormat(rawReply, verificationChunks, modelUsed);

        // Record compact user question + verified answer into history only if session was not reset mid-flight
        synchronized (this) {
            if (sessionGeneration.get() == genAtStart) {
                int maxSingleMessageChars = maxHistoryChars / 2;
                history.add(ChatMessage.user(truncateForHistory(formatCompactHistoryUserEntry(userQuestion, codeContext), maxSingleMessageChars)));
                history.add(ChatMessage.assistant(truncateForHistory(verified.answer(), maxSingleMessageChars)));
                trimHistory();
            }
        }
        return verified;
    }

    synchronized void trimHistory() {
        while (totalHistoryChars() > maxHistoryChars && history.size() > 3) {
            int nextUserIdx = findNextUserMessageIndex(2);
            if (nextUserIdx <= 1 || nextUserIdx >= history.size() - 1) {
                break;
            }
            droppedMessagesCount += (nextUserIdx - 1);
            history.subList(1, nextUserIdx).clear();
        }
        if (totalHistoryChars() > maxHistoryChars && history.size() >= 3) {
            int systemLen = history.get(0).content().length();
            int remainingBudget = Math.max(0, maxHistoryChars - systemLen);
            int userIdx = history.size() - 2;
            int assistantIdx = history.size() - 1;
            ChatMessage userMsg = history.get(userIdx);
            ChatMessage assistantMsg = history.get(assistantIdx);
            int userBudget = Math.min(userMsg.content().length(), remainingBudget / 2);
            if (userMsg.content().length() > userBudget) {
                userMsg = new ChatMessage(userMsg.role(), truncateForHistory(userMsg.content(), userBudget));
                history.set(userIdx, userMsg);
            }
            int assistantBudget = Math.max(0, remainingBudget - userMsg.content().length());
            if (assistantMsg.content().length() > assistantBudget) {
                assistantMsg = new ChatMessage(assistantMsg.role(), truncateForHistory(assistantMsg.content(), assistantBudget));
                history.set(assistantIdx, assistantMsg);
            }
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

    private static String formatCompactHistoryUserEntry(String userQuestion, EditorCodeContext codeContext) {
        String q = userQuestion == null ? "" : userQuestion.trim();
        if (codeContext != null && codeContext.hasSelection()) {
            return "[Code Context: " + codeContext.formatSummary() + "] " + q;
        }
        return q;
    }

    private static String truncateForHistory(String content, int maxChars) {
        if (content == null || maxChars <= 0) {
            return "";
        }
        if (content.length() <= maxChars) {
            return content;
        }
        String suffix = "... [truncated]";
        if (maxChars <= suffix.length()) {
            return content.substring(0, maxChars);
        }
        int cut = maxChars - suffix.length();
        return content.substring(0, cut) + suffix;
    }

    private String buildRetrievalQuery(String userQuestion, EditorCodeContext codeContext) {
        StringBuilder q = new StringBuilder();
        if (userQuestion != null) {
            q.append(userQuestion);
        }
        // Include the previous user question only when the current question is a coreference/follow-up question
        if (userQuestion != null && isFollowUpQuestion(userQuestion) && history.size() >= 3) {
            for (int i = history.size() - 1; i >= 1; i--) {
                if (ChatMessage.USER.equals(history.get(i).role())) {
                    q.append(" ").append(history.get(i).content());
                    break;
                }
            }
        }
        if (codeContext != null && codeContext.hasSelection()) {
            q.append(" ").append(codeContext.className())
                    .append(" ").append(codeContext.methodName())
                    .append(" ").append(codeContext.selectedCode());
        }
        return q.toString().trim();
    }

    static boolean isFollowUpQuestion(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return false;
        }
        String trimmed = userQuestion.trim();
        if (FOLLOW_UP_MARKER_PATTERN.matcher(trimmed).find()) {
            return true;
        }
        String[] words = trimmed.split("\\s+");
        return words.length < 3 && !STANDALONE_TECH_NOUN_PATTERN.matcher(trimmed).find();
    }
}
