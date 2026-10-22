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
}
