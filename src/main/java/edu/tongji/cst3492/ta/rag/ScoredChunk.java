package edu.tongji.cst3492.ta.rag;

import java.util.Objects;

/**
 * Represents a retrieved knowledge chunk along with its relevance score and retrieval method breakdown.
 */
public record ScoredChunk(
        KnowledgeChunk chunk,
        double score,
        double keywordScore,
        double vectorScore
) implements Comparable<ScoredChunk> {

    public ScoredChunk {
        Objects.requireNonNull(chunk, "chunk must not be null");
    }

    public ScoredChunk(KnowledgeChunk chunk, double score) {
        this(chunk, score, score, 0.0);
    }

    @Override
    public int compareTo(ScoredChunk other) {
        return Double.compare(other.score, this.score);
    }
}
