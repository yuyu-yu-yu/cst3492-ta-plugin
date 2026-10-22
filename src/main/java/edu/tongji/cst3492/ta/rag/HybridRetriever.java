package edu.tongji.cst3492.ta.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid RAG retriever combining {@link KeywordIndex} (BM25) and {@link VectorIndex} (Cosine Similarity)
 * using Reciprocal Rank Fusion (RRF, k = 60) as taught in Lecture 5 (Slide 26-27):
 * {@code score(p) = sum(1.0 / (60 + rank))}
 */
public final class HybridRetriever {

    public static final int DEFAULT_RRF_K = 60;
    public static final int DEFAULT_CANDIDATE_WINDOW = 15;
    public static final double DEFAULT_MIN_RELEVANCE_SCORE = 0.015;
    public static final double MIN_BM25_SCORE = 2.0;
    public static final double MIN_SEMANTIC_ONLY_COSINE = 0.42;

    private final KeywordIndex keywordIndex;
    private final VectorIndex vectorIndex;
    private final int rrfK;
    private final double minRelevanceScore;

    public HybridRetriever(List<KnowledgeChunk> chunks) {
        this(new KeywordIndex(chunks), new VectorIndex(chunks, new EmbeddingModel()), DEFAULT_RRF_K, DEFAULT_MIN_RELEVANCE_SCORE);
    }

    public HybridRetriever(KeywordIndex keywordIndex, VectorIndex vectorIndex, int rrfK, double minRelevanceScore) {
        this.keywordIndex = keywordIndex;
        this.vectorIndex = vectorIndex;
        this.rrfK = rrfK;
        this.minRelevanceScore = minRelevanceScore;
    }

    public List<ScoredChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        int window = Math.max(DEFAULT_CANDIDATE_WINDOW, topK * 3);
        List<ScoredChunk> keywordRanking = keywordIndex.search(query, window);
        List<ScoredChunk> vectorRanking = vectorIndex.search(query, window);

        boolean hasKeywordMatch = !keywordRanking.isEmpty() && keywordRanking.get(0).keywordScore() >= MIN_BM25_SCORE;
        boolean hasSemanticMatch = !vectorRanking.isEmpty() && vectorRanking.get(0).vectorScore() >= MIN_SEMANTIC_ONLY_COSINE;
        if (!hasKeywordMatch && !hasSemanticMatch) {
            return List.of();
        }

        Map<String, Double> fusedScores = new LinkedHashMap<>();
        Map<String, Double> kwScores = new HashMap<>();
        Map<String, Double> vecScores = new HashMap<>();
        Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();

        for (int rank = 1; rank <= keywordRanking.size(); rank++) {
            ScoredChunk sc = keywordRanking.get(rank - 1);
            String id = sc.chunk().id();
            fusedScores.merge(id, 1.0 / (rrfK + rank), Double::sum);
            kwScores.put(id, sc.keywordScore());
            byId.put(id, sc.chunk());
        }

        for (int rank = 1; rank <= vectorRanking.size(); rank++) {
            ScoredChunk sc = vectorRanking.get(rank - 1);
            String id = sc.chunk().id();
            fusedScores.merge(id, 1.0 / (rrfK + rank), Double::sum);
            vecScores.put(id, sc.vectorScore());
            byId.put(id, sc.chunk());
        }

        List<ScoredChunk> merged = new ArrayList<>();
        for (Map.Entry<String, Double> entry : fusedScores.entrySet()) {
            String id = entry.getKey();
            double rrfScore = entry.getValue();
            double kw = kwScores.getOrDefault(id, 0.0);
            double vec = vecScores.getOrDefault(id, 0.0);
            if (rrfScore >= minRelevanceScore && (kw >= MIN_BM25_SCORE || vec >= MIN_SEMANTIC_ONLY_COSINE)) {
                merged.add(new ScoredChunk(byId.get(id), rrfScore, kw, vec));
            }
        }

        Collections.sort(merged);
        return merged.size() <= topK ? merged : List.copyOf(merged.subList(0, topK));
    }

    public KeywordIndex keywordIndex() {
        return keywordIndex;
    }

    public VectorIndex vectorIndex() {
        return vectorIndex;
    }
}
