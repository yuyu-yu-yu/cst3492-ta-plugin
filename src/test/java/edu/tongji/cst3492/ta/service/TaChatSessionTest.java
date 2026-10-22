package edu.tongji.cst3492.ta.service;

import edu.tongji.cst3492.ta.PluginConstants;
import edu.tongji.cst3492.ta.llm.EditorCodeContext;
import edu.tongji.cst3492.ta.llm.OfflineCourseTaClient;
import edu.tongji.cst3492.ta.llm.TaResponse;
import edu.tongji.cst3492.ta.rag.CourseKnowledgeBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TaChatSessionTest {

    private static CourseKnowledgeBase knowledgeBase;

    @BeforeAll
    static void setUp() throws Exception {
        knowledgeBase = new CourseKnowledgeBase();
        knowledgeBase.indexFile(Path.of("Lec-5-Building-AI-Powered-Java-Applications.pdf"));
    }

    @Test
    void answersCourseQuestionWithSlideCitationAndGeneralQuestionWithFallback() {
        TaChatSession session = new TaChatSession(knowledgeBase, new OfflineCourseTaClient());

        // 1. Course-specific question -> must cite Lecture 5 slide & page number
        TaResponse courseAnswer = session.ask(
                "How does ToolDispatcher check tool calls and why is MAX_TOOL_ROUNDS needed?",
                EditorCodeContext.empty()
        );
        assertTrue(courseAnswer.basedOnCourseMaterial());
        assertFalse(courseAnswer.citedChunks().isEmpty());
        assertTrue(courseAnswer.citationSummary().contains("Lec-5-Building-AI-Powered-Java-Applications.pdf"));
        assertTrue(courseAnswer.citationSummary().contains("Page "));

        // 2. Unrelated question -> must explicitly state general-knowledge fallback
        TaResponse generalAnswer = session.ask(
                "How do I configure CSS flexbox layout in a React frontend?",
                EditorCodeContext.empty()
        );
        assertFalse(generalAnswer.basedOnCourseMaterial());
        assertTrue(generalAnswer.citedChunks().isEmpty());
        assertTrue(generalAnswer.answer().contains(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK));
    }

    @Test
    void trimsOldestExchangeWhenHistoryLimitExceededWhileKeepingSystemPrompt() {
        // Use a small maxHistoryChars limit (1800) to trigger history trimming across multiple turns
        TaChatSession session = new TaChatSession(knowledgeBase, new OfflineCourseTaClient(), 1800);

        for (int i = 1; i <= 5; i++) {
            session.ask("Explain RAG step " + i + " and hybrid search rank fusion in Lecture 5.", EditorCodeContext.empty());
        }

        assertEquals("system", session.history().get(0).role(), "System prompt must always remain at index 0");
        assertTrue(session.droppedMessagesCount() > 0, "Old exchanges should be trimmed once maxHistoryChars is exceeded");
    }

    @Test
    void storesCompactUserQuestionInHistoryInsteadOfFullRagChunks() {
        TaChatSession session = new TaChatSession(knowledgeBase, new OfflineCourseTaClient());
        session.ask("What are the four steps of RAG in Lecture 5?", EditorCodeContext.empty());
        session.ask("How does Reciprocal Rank Fusion combine rankings?", EditorCodeContext.empty());

        // History should retain system + Turn 1 (user+assistant) + Turn 2 (user+assistant) = 5 messages
        assertEquals(5, session.history().size(), "Multi-turn history should retain earlier exchanges because RAG chunks are not persisted in history");
        String storedUserTurn1 = session.history().get(1).content();
        assertFalse(storedUserTurn1.contains("=== RETRIEVED COURSE MATERIALS"), "History user entry must not store bulky retrieved chunks");
    }

    @Test
    void surfacesOnlineLlmFailureExplicitlyWithoutSilentOfflineTemplateMasking() {
        TaChatSession session = new TaChatSession(knowledgeBase, new edu.tongji.cst3492.ta.llm.ModelClient() {
            @Override
            public String complete(java.util.List<edu.tongji.cst3492.ta.llm.ChatMessage> messages) {
                throw new RuntimeException("HTTP 401 Unauthorized: Invalid API Key");
            }

            @Override
            public String providerName() {
                return "DeepSeek-Live";
            }
        });

        TaResponse response = session.ask("What are the four steps of RAG?", EditorCodeContext.empty());
        assertFalse(response.basedOnCourseMaterial(), "Failed online call must not pretend to be a verified course answer");
        assertTrue(response.answer().contains("HTTP 401 Unauthorized"));
        assertTrue(response.modelUsed().contains("Error"));
    }

    @Test
    void verifiesMultiTurnCumulativeSessionChunksAndAvoidsNonFollowUpQueryPollutionAndCapsHistoryLength() {
        // 1. Multi-turn cumulative citation verification + coreference-only follow-up retrieval
        final java.util.List<String> capturedUserPrompts = new java.util.ArrayList<>();
        TaChatSession session = new TaChatSession(knowledgeBase, new edu.tongji.cst3492.ta.llm.ModelClient() {
            private int callCount = 0;

            @Override
            public String complete(java.util.List<edu.tongji.cst3492.ta.llm.ChatMessage> messages) {
                callCount++;
                capturedUserPrompts.add(messages.get(messages.size() - 1).content());
                if (callCount == 1) {
                    return "RAG has four steps [Source: Lec-5-Building-AI-Powered-Java-Applications.pdf, Page 23].";
                } else if (callCount == 2) {
                    // Turn 2 asks "What is JPA?" (not a follow-up to RAG, no chunks retrieved),
                    // but if the model re-cites Turn 1's slide 23 in a comparison, cumulative session chunks verify it
                    return "JPA is for ORM, unlike the RAG pipeline in [Source: Lec-5-Building-AI-Powered-Java-Applications.pdf, Page 23].";
                }
                return "A".repeat(2000) + " [Source: Lec-5-Building-AI-Powered-Java-Applications.pdf, Page 23]";
            }

            @Override
            public String providerName() {
                return "MockMultiTurnClient";
            }
        }, 1800);

        long genBefore = session.currentGeneration();
        TaResponse turn1 = session.ask("What are the four steps of RAG in Lecture 5?", EditorCodeContext.empty());
        assertTrue(turn1.basedOnCourseMaterial());

        // Turn 2: standalone technical question "What is JPA?" (< 32 chars) must NOT append Turn 1's RAG question
        TaResponse turn2 = session.ask("What is JPA?", EditorCodeContext.empty());
        assertTrue(
                capturedUserPrompts.get(1).contains("NONE (No matching course slides or notes met the relevance threshold"),
                "'What is JPA?' must not inherit previous RAG question for retrieval"
        );
        assertTrue(turn2.basedOnCourseMaterial(), "Turn 2 re-citing Turn 1's slide must be verified via sessionSuppliedChunks");
        assertFalse(turn2.answer().contains("[Unverified Source removed]"));

        // Turn 3: oversized response must be truncated so totalHistoryChars() <= maxHistoryChars
        session.ask("Can you elaborate on that?", EditorCodeContext.empty());
        assertTrue(session.totalHistoryChars() <= 1800, "totalHistoryChars() must never exceed maxHistoryChars after trimHistory()");
        assertTrue(
                session.history().get(session.history().size() - 1).content().endsWith("... [truncated]"),
                "Oversized assistant message in history must be truncated with '... [truncated]'"
        );

        session.resetSession();
        assertTrue(session.currentGeneration() > genBefore, "resetSession() must increment currentGeneration()");
    }
}
