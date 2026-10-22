package edu.tongji.cst3492.ta.rag;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridRetrieverTest {

    private static CourseKnowledgeBase knowledgeBase;

    @BeforeAll
    static void setUpKnowledgeBase() throws Exception {
        knowledgeBase = new CourseKnowledgeBase();
        knowledgeBase.indexFile(Path.of("Lec-5-Building-AI-Powered-Java-Applications.pdf"));
    }

    @Test
    void retrievesReciprocalRankFusionSlideWithExactPageCitation() {
        List<ScoredChunk> results = knowledgeBase.retrieve("How does Reciprocal Rank Fusion merge BM25 and vector rankings?", 3);
        assertFalse(results.isEmpty(), "Should retrieve relevant chunks for RRF query");

        boolean hasPage27 = results.stream()
                .anyMatch(sc -> sc.chunk().startPage() == 27
                        && sc.chunk().documentName().equals("Lec-5-Building-AI-Powered-Java-Applications.pdf"));
        assertTrue(hasPage27, "Top-3 results should include Slide 27 (Merging Rankings: Reciprocal Rank Fusion)");
    }

    @Test
    void semanticVectorSearchFindsMapPassageForDictionaryLookup() {
        // Slide 25/26 example: query "dictionary lookup" should match maps-1 / key-value pairs via vector similarity
        List<ScoredChunk> results = knowledgeBase.retrieve("dictionary lookup key value pairs", 3);
        assertFalse(results.isEmpty(), "Semantic search should find Map/dictionary slides");
        boolean foundMapSlide = results.stream()
                .anyMatch(sc -> sc.chunk().content().toLowerCase().contains("map"));
        assertTrue(foundMapSlide, "Should retrieve slide discussing Maps for 'dictionary lookup'");
    }

    @Test
    void returnsEmptyListForUnrelatedGeneralKnowledgeQuery() {
        // Unrelated query with no course material coverage should return empty so TA states general knowledge fallback
        List<ScoredChunk> results = knowledgeBase.retrieve("How to bake sourdough bread with olive oil in an oven?", 3);
        assertTrue(results.isEmpty(), "Unrelated query must return empty chunks to trigger general-knowledge notice");
    }
}
