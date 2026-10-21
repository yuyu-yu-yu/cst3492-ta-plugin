package edu.tongji.cst3492.ta.rag;

import java.util.Objects;

/**
 * Immutable knowledge chunk extracted from a course document with verifiable source and page scope.
 */
public record KnowledgeChunk(
        String id,
        String documentName,
        int startPage,
        int endPage,
        String sectionTitle,
        String content
) {
    public KnowledgeChunk {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(documentName, "documentName must not be null");
        Objects.requireNonNull(sectionTitle, "sectionTitle must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("chunk id must not be blank");
        }
        if (documentName.isBlank()) {
            throw new IllegalArgumentException("documentName must not be blank");
        }
        if (startPage < 1 || endPage < startPage) {
            throw new IllegalArgumentException("invalid page scope: " + startPage + "-" + endPage);
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    public String pageScope() {
        return startPage == endPage
                ? "Page " + startPage
                : "Pages " + startPage + "-" + endPage;
    }

    public String formatCitation() {
        return "[Source: " + documentName + ", " + pageScope() + "]";
    }
}
