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
        assertTrue(
                knowledgeBase.retrieve("What is a HashMap?", 3).isEmpty(),
                "Generic 'What is a HashMap?' must not false-positive on Lecture 5 example label"
        );
        assertTrue(
                knowledgeBase.retrieve("HashMap vs TreeMap", 3).isEmpty(),
                "Generic 'HashMap vs TreeMap' must not false-positive on Lecture 5"
        );
        assertTrue(
                knowledgeBase.retrieve("How do I iterate over a HashMap?", 3).isEmpty(),
                "Generic 'How do I iterate over a HashMap?' must not false-positive on Lecture 5"
        );
        assertTrue(
                knowledgeBase.retrieve("How do I sort a List in Java?", 3).isEmpty(),
                "Generic 'How do I sort a List in Java?' must not false-positive on Lecture 5"
        );
        assertTrue(
                knowledgeBase.retrieve("How does a database index speed up SQL queries?", 3).isEmpty(),
                "Generic database index SQL query must not false-positive on Lecture 5 inverted index slide"
        );
        assertTrue(
                knowledgeBase.retrieve("try catch finally", 3).isEmpty(),
                "Generic 'try catch finally' must not false-positive on Lecture 5 example label"
        );
    }

    @Test
    void retrievesPromptInjectionSlide29AndCitationCheckingSlide28() {
        List<ScoredChunk> promptInjectionResults = knowledgeBase.retrieve(
                "What is prompt injection and how to defend against it?",
                3
        );
        assertFalse(promptInjectionResults.isEmpty(), "Prompt injection defense query should retrieve Lecture 5 slides");
        assertTrue(
                promptInjectionResults.stream().anyMatch(sc -> sc.chunk().startPage() == 29),
                "Should retrieve Slide 29 (Data Is Not Instructions) for prompt injection defense query"
        );

        List<ScoredChunk> citationCheckResults = knowledgeBase.retrieve(
                "check that the model only cites passages it was given",
                3
        );
        assertFalse(citationCheckResults.isEmpty(), "Citation checking query should retrieve Lecture 5 slides");
        assertTrue(
                citationCheckResults.stream().anyMatch(sc -> sc.chunk().startPage() == 28),
                "Should retrieve Slide 28 (Checking What the Model Cites)"
        );
    }

    @Test
    void deduplicatesMultipleChunksFromSameSlidePageInTopK() {
        KnowledgeChunk p15a = new KnowledgeChunk("doc-p15-1", "Lec-5.pdf", 15, 15, "RAG Pipeline", "Reciprocal Rank Fusion BM25 vector part 1");
        KnowledgeChunk p15b = new KnowledgeChunk("doc-p15-2", "Lec-5.pdf", 15, 15, "RAG Pipeline", "Reciprocal Rank Fusion BM25 vector part 2");
        KnowledgeChunk p27 = new KnowledgeChunk("doc-p27-1", "Lec-5.pdf", 27, 27, "RRF Merging", "Reciprocal Rank Fusion BM25 vector score formula");
        HybridRetriever retriever = new HybridRetriever(List.of(p15a, p15b, p27));

        List<ScoredChunk> top = retriever.retrieve("Reciprocal Rank Fusion BM25 vector", 3);
        long page15Count = top.stream().filter(sc -> sc.chunk().startPage() == 15).count();
        assertEquals(1, page15Count, "At most one chunk per document page should be returned in topK");
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
