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

    @Test
    void doesNotFalsePositiveOnUncoveredJavaEnterpriseTopics() {
        // Review probe cases: general Java questions not covered in Lecture 5 must return empty
        assertTrue(
                knowledgeBase.retrieve("How does Spring Boot @Autowired dependency injection work?", 3).isEmpty(),
                "Spring Boot DI must not false-positive on Lecture 5 prompt injection slide"
        );
        assertTrue(
                knowledgeBase.retrieve("How does Java garbage collection work?", 3).isEmpty(),
                "Java GC must not false-positive on Lecture 5 cover slide"
        );
        assertTrue(
                knowledgeBase.retrieve("Explain optimistic locking in Java JPA", 3).isEmpty(),
                "Java JPA optimistic locking must not false-positive on Lecture 5 slides"
        );
    }

    @Test
    void workspaceScanIgnoresAssignmentPdfsAndReadme() {
        CourseKnowledgeBase kb = new CourseKnowledgeBase();
        kb.scanDirectoryForCoursePdfs(Path.of("."));
        assertFalse(kb.documentSummary().containsKey("README.md"), "Workspace scan must not index README.md");
        assertFalse(kb.documentSummary().containsKey("AY202627S1-CST3492-Assignment-A.pdf"), "Workspace scan must not index Assignment-A.pdf");
        assertTrue(kb.documentSummary().containsKey("Lec-5-Building-AI-Powered-Java-Applications.pdf"), "Workspace scan should index Lec-5 PDF");
    }
}
