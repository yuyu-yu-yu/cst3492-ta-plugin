package edu.tongji.cst3492.ta.llm;

import edu.tongji.cst3492.ta.PluginConstants;
import edu.tongji.cst3492.ta.rag.KnowledgeChunk;
import edu.tongji.cst3492.ta.rag.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitationVerifierAndClientTest {

    @Test
    void enforcesGeneralKnowledgeNoticeWhenNoCourseChunksRetrieved() {
        OfflineCourseTaClient client = new OfflineCourseTaClient();
        String userPrompt = TaPromptBuilder.buildAugmentedUserMessage(
                "How do I configure a Kubernetes Ingress controller?",
                EditorCodeContext.empty(),
                List.of()
        );
        String rawReply = client.complete(List.of(
                ChatMessage.system(TaPromptBuilder.SYSTEM_PROMPT),
                ChatMessage.user(userPrompt)
        ));

        TaResponse verified = CitationVerifier.verifyAndFormat(rawReply, List.of(), client.providerName());
        assertFalse(verified.basedOnCourseMaterial());
        assertTrue(verified.citedChunks().isEmpty());
        assertTrue(verified.answer().contains(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK));
        assertEquals(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK, verified.citationSummary());
    }

    @Test
    void attachesVerifiableDocumentAndPageScopeWhenCourseMaterialUsed() {
        KnowledgeChunk slide23 = new KnowledgeChunk(
                "lec-5-p23-1",
                "Lec-5-Building-AI-Powered-Java-Applications.pdf",
                23,
                23,
                "RAG in Four Steps",
                "1 Chunk: Split the notes into passages with ids. 2 Index: Build a keyword index and a vector per passage. 3 Retrieve: Find the top passages. 4 Generate: Put the passages in the message; the model cites ids."
        );
        List<ScoredChunk> retrieved = List.of(new ScoredChunk(slide23, 0.032, 4.2, 0.85));

        OfflineCourseTaClient client = new OfflineCourseTaClient();
        String userPrompt = TaPromptBuilder.buildAugmentedUserMessage(
                "What are the four steps of RAG in Lecture 5?",
                EditorCodeContext.empty(),
                retrieved
        );
        String rawReply = client.complete(List.of(
                ChatMessage.system(TaPromptBuilder.SYSTEM_PROMPT),
                ChatMessage.user(userPrompt)
        ));

        TaResponse verified = CitationVerifier.verifyAndFormat(rawReply, retrieved, client.providerName());
        assertTrue(verified.basedOnCourseMaterial());
        assertEquals(1, verified.citedChunks().size());
        assertEquals(
                "[Source: Lec-5-Building-AI-Powered-Java-Applications.pdf, Page 23]",
                verified.citationSummary()
        );
        assertTrue(verified.answer().contains("Lec-5-Building-AI-Powered-Java-Applications.pdf"));
        assertTrue(verified.answer().contains("Page 23"));
    }

    @Test
    void includesEditorCodeContextInPromptAndResponse() {
        EditorCodeContext codeContext = new EditorCodeContext(
                "HybridRetriever.java",
                "HybridRetriever",
                "retrieve",
                45,
                62,
                "fusedScores.merge(id, 1.0 / (rrfK + rank), Double::sum);"
        );
        KnowledgeChunk slide27 = new KnowledgeChunk(
                "lec-5-p27-1",
                "Lec-5-Building-AI-Powered-Java-Applications.pdf",
                27,
                27,
                "Merging Rankings: Reciprocal Rank Fusion",
                "score(p) = sum 1 / (60 + rank of p). Ranks, not scores: BM25 and cosine are on different scales."
        );
        List<ScoredChunk> retrieved = List.of(new ScoredChunk(slide27, 0.033, 5.1, 0.91));

        String augmentedPrompt = TaPromptBuilder.buildAugmentedUserMessage(
                "Explain this RRF score merging line in my selected code.",
                codeContext,
                retrieved
        );
        assertTrue(augmentedPrompt.contains("HybridRetriever.java (Lines 45-62)"));
        assertTrue(augmentedPrompt.contains("fusedScores.merge"));

        OfflineCourseTaClient client = new OfflineCourseTaClient();
        String reply = client.complete(List.of(
                ChatMessage.system(TaPromptBuilder.SYSTEM_PROMPT),
                ChatMessage.user(augmentedPrompt)
        ));
        TaResponse verified = CitationVerifier.verifyAndFormat(reply, retrieved, client.providerName());
        assertTrue(verified.basedOnCourseMaterial());
        assertTrue(verified.answer().contains("Page 27"));
    }
}
