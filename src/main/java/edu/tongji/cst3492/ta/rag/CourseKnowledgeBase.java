package edu.tongji.cst3492.ta.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the indexed course materials for the TA plugin.
 * Pre-loads the bundled CST3492 Lecture 5 slide deck on startup, scans workspace course PDFs,
 * and allows users to dynamically import additional lecture slides or notes at runtime.
 */
public final class CourseKnowledgeBase {

    private static final String BUNDLED_LEC5_RESOURCE = "/courses/Lec-5-Building-AI-Powered-Java-Applications.pdf";
    private static final String BUNDLED_LEC5_NAME = "Lec-5-Building-AI-Powered-Java-Applications.pdf";

    private final CourseDocumentParser parser;
    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> documentChunkCounts = new LinkedHashMap<>();
    private volatile HybridRetriever retriever;

    public CourseKnowledgeBase() {
        this(new CourseDocumentParser());
    }

    public CourseKnowledgeBase(CourseDocumentParser parser) {
        this.parser = parser;
        this.retriever = new HybridRetriever(List.of());
    }

    public synchronized void loadBundledCourseMaterials() {
        try (InputStream is = CourseKnowledgeBase.class.getResourceAsStream(BUNDLED_LEC5_RESOURCE)) {
            if (is != null) {
                List<KnowledgeChunk> parsed = parser.parsePdfStream(BUNDLED_LEC5_NAME, is);
                replaceDocumentChunks(BUNDLED_LEC5_NAME, parsed);
            }
        } catch (IOException ignored) {
            // Fallback to workspace scan if resource stream unavailable
        }
    }

    public synchronized int scanDirectoryForCoursePdfs(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return 0;
        }
        int loadedDocs = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{pdf,md,txt}")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.contains("Assignment") && name.contains("Schedule")) {
                    continue;
                }
                if (!documentChunkCounts.containsKey(name)) {
                    indexFile(file);
                    loadedDocs++;
                }
            }
        } catch (IOException ignored) {
        }
        return loadedDocs;
    }

    public synchronized List<KnowledgeChunk> indexFile(Path file) throws IOException {
        List<KnowledgeChunk> parsed = parser.parseFile(file);
        replaceDocumentChunks(file.getFileName().toString(), parsed);
        return parsed;
    }

    public synchronized void addChunks(String documentName, List<KnowledgeChunk> newChunks) {
        replaceDocumentChunks(documentName, newChunks);
    }

    private void replaceDocumentChunks(String documentName, List<KnowledgeChunk> newChunks) {
        chunks.removeIf(c -> c.documentName().equals(documentName));
        chunks.addAll(newChunks);
        documentChunkCounts.put(documentName, newChunks.size());
        this.retriever = new HybridRetriever(new ArrayList<>(chunks));
    }

    public List<ScoredChunk> retrieve(String query, int topK) {
        return retriever.retrieve(query, topK);
    }

    public synchronized List<KnowledgeChunk> allChunks() {
        return List.copyOf(chunks);
    }

    public synchronized Map<String, Integer> documentSummary() {
        return Map.copyOf(documentChunkCounts);
    }

    public synchronized int totalChunks() {
        return chunks.size();
    }
}
