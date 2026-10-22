package edu.tongji.cst3492.ta.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory vector index supporting cosine similarity search over embedded {@link KnowledgeChunk}s.
 * Mirrors Lecture 5 Slide 25 ("Vector Search: Similar Meaning").
 */
public final class VectorIndex {

    private final List<KnowledgeChunk> chunks;
    private final EmbeddingModel embeddingModel;
    private final float[][] embeddings;

    public VectorIndex(List<KnowledgeChunk> chunks, EmbeddingModel embeddingModel) {
        this.chunks = List.copyOf(chunks);
        this.embeddingModel = embeddingModel;
        this.embeddings = new float[this.chunks.size()][];
        for (int i = 0; i < this.chunks.size(); i++) {
            KnowledgeChunk chunk = this.chunks.get(i);
            this.embeddings[i] = embeddingModel.embed(chunk.sectionTitle() + " " + chunk.content());
        }
    }

    public List<ScoredChunk> search(String query, int topK) {
        if (query == null || query.isBlank() || chunks.isEmpty() || topK <= 0) {
            return List.of();
        }
        float[] queryVec = embeddingModel.embed(query);
        List<ScoredChunk> results = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double sim = EmbeddingModel.cosineSimilarity(queryVec, embeddings[i]);
            if (sim > 0.15) {
                results.add(new ScoredChunk(chunks.get(i), sim, 0.0, sim));
            }
        }
        Collections.sort(results);
        return results.size() <= topK ? results : List.copyOf(results.subList(0, topK));
    }

    public int size() {
        return chunks.size();
    }
}
