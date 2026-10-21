package edu.tongji.cst3492.ta.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CourseDocumentParserTest {

    @Test
    void parsesLecture5PdfWithPageScopeMetadata() throws Exception {
        CourseDocumentParser parser = new CourseDocumentParser();
        Path pdfPath = Path.of("Lec-5-Building-AI-Powered-Java-Applications.pdf");
        assertTrue(pdfPath.toFile().exists(), "Lecture 5 PDF should exist in project root");

        List<KnowledgeChunk> chunks = parser.parseFile(pdfPath);
        assertFalse(chunks.isEmpty(), "Should extract knowledge chunks from Lecture 5 PDF");

        // Verify page numbers are preserved across the 44-page slide deck
        boolean foundPage23 = chunks.stream()
                .anyMatch(c -> c.startPage() == 23 && c.content().contains("RAG"));
        assertTrue(foundPage23, "Page 23 (RAG in Four Steps) must be indexed with page number 23");

        boolean foundPage27 = chunks.stream()
                .anyMatch(c -> c.startPage() == 27 && c.content().contains("Reciprocal Rank Fusion"));
        assertTrue(foundPage27, "Page 27 (Reciprocal Rank Fusion) must be indexed with page number 27");

        KnowledgeChunk sample = chunks.stream().filter(c -> c.startPage() == 23).findFirst().orElseThrow();
        assertEquals("[Source: Lec-5-Building-AI-Powered-Java-Applications.pdf, Page 23]", sample.formatCitation());
    }

    @Test
    void splitsLongPageWithOverlapWhileKeepingPageNumber() {
        CourseDocumentParser parser = new CourseDocumentParser(200, 40);
        String longText = "Java Enterprise Application Development covers RAG pipelines. ".repeat(12);
        List<CourseDocumentParser.PageContent> pages = List.of(
                new CourseDocumentParser.PageContent(15, "RAG Architecture", longText)
        );

        List<KnowledgeChunk> chunks = parser.chunkPages("Lec-5.pdf", pages);
        assertTrue(chunks.size() >= 3, "Long page should be split into multiple overlapping chunks");
        for (KnowledgeChunk chunk : chunks) {
            assertEquals("Lec-5.pdf", chunk.documentName());
            assertEquals(15, chunk.startPage());
            assertEquals("Page 15", chunk.pageScope());
            assertTrue(chunk.content().length() <= 200);
        }
    }

    @Test
    void tokenizesCamelCaseAndTechnicalTerms() {
        List<String> tokens = TextTokenizer.tokenize("ConcurrentModificationException in HybridRetriever_BM25");
        assertTrue(tokens.contains("concurrentmodificationexception"));
        assertTrue(tokens.contains("concurrent"));
        assertTrue(tokens.contains("modification"));
        assertTrue(tokens.contains("exception"));
        assertTrue(tokens.contains("hybrid"));
        assertTrue(tokens.contains("retriever"));
        assertTrue(tokens.contains("bm25"));
    }
}
