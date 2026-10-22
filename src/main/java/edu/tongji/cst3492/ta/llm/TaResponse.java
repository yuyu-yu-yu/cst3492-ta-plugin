package edu.tongji.cst3492.ta.llm;

import edu.tongji.cst3492.ta.rag.KnowledgeChunk;

import java.util.List;
import java.util.Objects;

/**
 * Verified TA response containing the answer text, verified course material citations
 * (document name + page number/scope), or an explicit general-knowledge fallback declaration.
 */
public record TaResponse(
        String answer,
        List<KnowledgeChunk> citedChunks,
        boolean basedOnCourseMaterial,
        String citationSummary,
        String modelUsed
) {
    public TaResponse {
        Objects.requireNonNull(answer, "answer must not be null");
        citedChunks = citedChunks == null ? List.of() : List.copyOf(citedChunks);
        Objects.requireNonNull(citationSummary, "citationSummary must not be null");
        Objects.requireNonNull(modelUsed, "modelUsed must not be null");
    }

    public List<String> formattedCitations() {
        return citedChunks.stream()
                .map(KnowledgeChunk::formatCitation)
                .distinct()
                .toList();
    }
}
