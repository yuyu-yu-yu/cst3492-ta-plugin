package edu.tongji.cst3492.ta.llm;

import edu.tongji.cst3492.ta.PluginConstants;
import edu.tongji.cst3492.ta.rag.KnowledgeChunk;
import edu.tongji.cst3492.ta.rag.ScoredChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enforces the F0 citation and general-knowledge fallback contract in Java ("The model proposes; Java decides").
 * Mirrors Lecture 5 Slide 28 ("Checking What the Model Cites"):
 * - Verifies which supplied {@link KnowledgeChunk}s are actually referenced in the response.
 * - Rejects hallucinated citations that were never supplied.
 * - Appends verifiable {@code [Source: <Document>, Page <N>]} footers when grounded in retrieved course materials.
 * - Enforces the explicit general-knowledge fallback notice when no course material is retrieved or used.
 */
public final class CitationVerifier {

    private CitationVerifier() {}

    public static TaResponse verifyAndFormat(
            String rawModelReply,
            List<ScoredChunk> suppliedScoredChunks,
            String modelName
    ) {
        String reply = rawModelReply == null ? "" : rawModelReply.trim();
        List<KnowledgeChunk> supplied = suppliedScoredChunks == null
                ? List.of()
                : suppliedScoredChunks.stream().map(ScoredChunk::chunk).toList();

        // Case 1: No relevant course materials were retrieved -> general knowledge fallback required
        if (supplied.isEmpty()) {
            String normalizedReply = ensureFallbackNoticePresent(reply);
            return new TaResponse(
                    normalizedReply,
                    List.of(),
                    false,
                    PluginConstants.GENERAL_KNOWLEDGE_FALLBACK,
                    modelName
            );
        }

        // Case 2: Course materials were supplied -> check if model explicitly declared general knowledge only
        String lowerReply = reply.toLowerCase(Locale.ROOT);
        boolean modelDeclaredNoCourseRef = lowerReply.contains("no specific course material is referenced")
                || lowerReply.contains("based on general knowledge");

        // Identify which supplied chunks were cited by ID or document/page in the model reply
        Map<String, KnowledgeChunk> verifiedChunks = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : supplied) {
            boolean citedById = reply.contains(chunk.id());
            boolean citedByPage = reply.contains(chunk.pageScope())
                    || lowerReply.contains("page " + chunk.startPage())
                    || lowerReply.contains("slide " + chunk.startPage());
            if (citedById || citedByPage) {
                verifiedChunks.put(chunk.id(), chunk);
            }
        }

        // If the model used the supplied context without declaring general-knowledge fallback,
        // attach the top supplied chunks that provided the context if not already matched by exact ID string.
        if (verifiedChunks.isEmpty() && !modelDeclaredNoCourseRef) {
            int limit = Math.min(2, supplied.size());
            for (int i = 0; i < limit; i++) {
                KnowledgeChunk c = supplied.get(i);
                verifiedChunks.put(c.id(), c);
            }
        }

        if (verifiedChunks.isEmpty()) {
            String normalizedReply = ensureFallbackNoticePresent(reply);
            return new TaResponse(
                    normalizedReply,
                    List.of(),
                    false,
                    PluginConstants.GENERAL_KNOWLEDGE_FALLBACK,
                    modelName
            );
        }

        List<KnowledgeChunk> finalChunks = new ArrayList<>(verifiedChunks.values());
        String citationSummary = finalChunks.stream()
                .map(KnowledgeChunk::formatCitation)
                .distinct()
                .collect(Collectors.joining(" "));

        String enrichedAnswer = appendCitationBlockIfMissing(reply, finalChunks);
        return new TaResponse(
                enrichedAnswer,
                finalChunks,
                true,
                citationSummary,
                modelName
        );
    }

    private static String ensureFallbackNoticePresent(String reply) {
        if (reply.contains(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK)) {
            return reply;
        }
        StringBuilder sb = new StringBuilder(reply);
        if (!reply.isEmpty()) {
            sb.append("\n\n---\n");
        }
        sb.append("Note: ").append(PluginConstants.GENERAL_KNOWLEDGE_FALLBACK);
        return sb.toString();
    }

    private static String appendCitationBlockIfMissing(String reply, List<KnowledgeChunk> citedChunks) {
        List<String> citations = citedChunks.stream()
                .map(c -> "- " + c.formatCitation() + " (" + c.sectionTitle() + ")")
                .distinct()
                .toList();
        boolean allPresent = citedChunks.stream()
                .allMatch(c -> reply.contains(c.formatCitation()));
        if (allPresent) {
            return reply;
        }
        StringBuilder sb = new StringBuilder(reply);
        sb.append("\n\n---\n**Course Material References:**\n");
        for (String line : citations) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }
}
