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

    @Test
    void doesNotFalseMatchPage2WhenModelCitesPage23() {
        KnowledgeChunk slide2 = new KnowledgeChunk(
                "lec-5-p2-1",
                "Lec-5-Building-AI-Powered-Java-Applications.pdf",
                2,
                2,
                "Development AI and Runtime AI",
                "Same discipline: the model drafts; Java and the teacher decide."
        );
        List<ScoredChunk> supplied = List.of(new ScoredChunk(slide2, 0.03, 3.0, 0.7));

        // Model mentions Page 23, which was NOT supplied (only Page 2 was supplied)
        TaResponse verified = CitationVerifier.verifyAndFormat(
                "According to Lec-5-Building-AI-Powered-Java-Applications.pdf on Page 23, RAG has four steps.",
                supplied,
                "TestModel"
        );
        assertFalse(verified.basedOnCourseMaterial(), "Page 23 must not substring-match supplied Page 2");
        assertTrue(verified.citedChunks().isEmpty());
        assertTrue(verified.answer().contains(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK));
    }

    @Test
    void stripsHallucinatedCitationsAndDoesNotAutoAttachSuppliedChunksWhenUncited() {
        KnowledgeChunk slide1 = new KnowledgeChunk(
                "lec-5-p1-1",
                "Lec-5-Building-AI-Powered-Java-Applications.pdf",
                1,
                1,
                "Cover",
                "Java Enterprise Application Development Lecture 5"
        );
        List<ScoredChunk> supplied = List.of(new ScoredChunk(slide1, 0.02, 2.5, 0.5));

        // 1. Model returns pure general knowledge without citing slide1
        TaResponse uncited = CitationVerifier.verifyAndFormat(
                "An ArrayList is backed by a dynamic array; a LinkedList uses doubly-linked nodes.",
                supplied,
                "TestModel"
        );
        assertFalse(uncited.basedOnCourseMaterial(), "Must NOT auto-attach supplied chunks when model did not cite them");
        assertTrue(uncited.citedChunks().isEmpty());
        assertTrue(uncited.answer().contains(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK));

        // 2. Model fabricates [Source: Fake.pdf, Page 999] and [ChunkID: fake-id-99]
        TaResponse hallucinated = CitationVerifier.verifyAndFormat(
                "See [Source: Fake.pdf, Page 999] and [ChunkID: fake-id-99] for details.",
                supplied,
                "TestModel"
        );
        assertFalse(hallucinated.basedOnCourseMaterial());
        assertFalse(hallucinated.answer().contains("Fake.pdf"));
        assertFalse(hallucinated.answer().contains("fake-id-99"));
        assertTrue(hallucinated.answer().contains("[Unverified Source removed]"));
        assertTrue(hallucinated.answer().contains("[Unverified ChunkID removed]"));
    }

    @Test
    void verifiesCitationsBeforeGeneralKnowledgeCheckAndSupportsFlexibleSyntaxAndPreventsCrossClauseMisbinding() {
        KnowledgeChunk slide23 = new KnowledgeChunk(
                "lec-5-p23-1",
                "Lec-5-Building-AI-Powered-Java-Applications.pdf",
                23,
                23,
                "RAG in Four Steps",
                "1 Chunk, 2 Index, 3 Retrieve, 4 Generate."
        );
        KnowledgeChunk docAPage23 = new KnowledgeChunk(
                "doc-a-p23-1",
                "A.pdf",
                23,
                23,
                "Unused Doc A",
                "Unrelated text on page 23 of A.pdf"
        );
        KnowledgeChunk docBPage23 = new KnowledgeChunk(
                "doc-b-p23-1",
                "B.pdf",
                23,
                23,
                "Doc B RAG",
                "RAG uses four steps."
        );

        // 1. Priority fix & flexible syntax ([Source: Lecture 5, Slide: 22-24])
        List<ScoredChunk> suppliedLec5 = List.of(new ScoredChunk(slide23, 0.03, 4.0, 0.8));
        TaResponse flexibleAndGeneralPhrase = CitationVerifier.verifyAndFormat(
                "This is not based on general knowledge alone; it comes from [Source: Lecture 5, Slides: 22–24].",
                suppliedLec5,
                "TestModel"
        );
        assertTrue(flexibleAndGeneralPhrase.basedOnCourseMaterial(), "Must verify citations before checking general knowledge phrase");
        assertEquals(1, flexibleAndGeneralPhrase.citedChunks().size());
        assertEquals("lec-5-p23-1", flexibleAndGeneralPhrase.citedChunks().get(0).id());

        // 2. Prevent cross-document prose misbinding across sentences
        List<ScoredChunk> suppliedAB = List.of(
                new ScoredChunk(docAPage23, 0.03, 3.5, 0.7),
                new ScoredChunk(docBPage23, 0.03, 4.1, 0.8)
        );
        TaResponse crossSentence = CitationVerifier.verifyAndFormat(
                "A.pdf was not used. RAG uses [Source: B.pdf, Page 23].",
                suppliedAB,
                "TestModel"
        );
        assertTrue(crossSentence.basedOnCourseMaterial());
        assertEquals(1, crossSentence.citedChunks().size(), "Must only verify B.pdf Page 23 and not misbind A.pdf Page 23 across sentences");
        assertEquals("B.pdf", crossSentence.citedChunks().get(0).documentName());
    }
}
