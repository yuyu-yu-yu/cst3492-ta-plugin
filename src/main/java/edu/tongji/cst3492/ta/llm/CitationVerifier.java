package edu.tongji.cst3492.ta.llm;

import edu.tongji.cst3492.ta.PluginConstants;
import edu.tongji.cst3492.ta.rag.KnowledgeChunk;
import edu.tongji.cst3492.ta.rag.ScoredChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enforces the F0 citation and general-knowledge fallback contract in Java ("The model proposes; Java decides").
 * Mirrors Lecture 5 Slide 28 ("Checking What the Model Cites"):
 * - Strictly parses explicit {@code [ChunkID: <id>]} and {@code [Source: <doc>, Page(s) <N>]} citations using regex word boundaries.
 * - Verifies every cited ID or (document + page) pair against the chunks Java actually supplied in this turn.
 * - Strips/flags any hallucinated citations that were never supplied.
 * - NEVER fabricates or auto-attaches citations when the model reply did not cite any supplied chunk.
 * - Enforces the explicit general-knowledge fallback notice when no supplied course material is cited.
 */
public final class CitationVerifier {

    private static final Pattern CHUNK_ID_PATTERN = Pattern.compile(
            "\\[ChunkID:\\s*([A-Za-z0-9_\\-]+)\\s*\\]",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SOURCE_CITATION_PATTERN = Pattern.compile(
            "\\[Source:\\s*([^,\\]]+?)\\s*,\\s*Pages?\\s*(\\d+)(?:\\s*-\\s*(\\d+))?\\s*\\]",
            Pattern.CASE_INSENSITIVE
    );

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

        // Always strip any hallucinated [ChunkID: ...] or [Source: ...] that were not supplied by Java
        String sanitizedReply = stripHallucinatedCitations(reply, supplied);

        // Case 1: No relevant course materials were retrieved -> general knowledge fallback required
        if (supplied.isEmpty()) {
            String normalizedReply = ensureFallbackNoticePresent(sanitizedReply);
            return new TaResponse(
                    normalizedReply,
                    List.of(),
                    false,
                    PluginConstants.GENERAL_KNOWLEDGE_FALLBACK,
                    modelName
            );
        }

        // Case 2: Course materials were supplied -> check if model explicitly declared general knowledge only
        String lowerReply = sanitizedReply.toLowerCase(Locale.ROOT);
        boolean modelDeclaredNoCourseRef = lowerReply.contains("no specific course material is referenced")
                || lowerReply.contains("based on general knowledge");
        if (modelDeclaredNoCourseRef) {
            String normalizedReply = ensureFallbackNoticePresent(sanitizedReply);
            return new TaResponse(
                    normalizedReply,
                    List.of(),
                    false,
                    PluginConstants.GENERAL_KNOWLEDGE_FALLBACK,
                    modelName
            );
        }

        // Case 3: Strictly verify which supplied chunks were cited by exact ChunkID or exact (Document + Page)
        Map<String, KnowledgeChunk> verifiedChunks = matchSuppliedCitations(sanitizedReply, supplied);

        // If the model did not cite any supplied chunk, NEVER auto-attach chunks; fall back to general knowledge notice
        if (verifiedChunks.isEmpty()) {
            String normalizedReply = ensureFallbackNoticePresent(sanitizedReply);
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

        String cleanedDisplayReply = cleanInlineChunkIdTags(sanitizedReply);
        String enrichedAnswer = appendCitationBlockIfMissing(cleanedDisplayReply, finalChunks);
        return new TaResponse(
                enrichedAnswer,
                finalChunks,
                true,
                citationSummary,
                modelName
        );
    }

    private static Map<String, KnowledgeChunk> matchSuppliedCitations(
            String reply,
            List<KnowledgeChunk> supplied
    ) {
        Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : supplied) {
            byId.put(chunk.id().toLowerCase(Locale.ROOT), chunk);
        }

        Map<String, KnowledgeChunk> verified = new LinkedHashMap<>();

        // 1. Match explicit [ChunkID: <id>] tags or exact word-bounded chunk IDs
        Matcher chunkIdMatcher = CHUNK_ID_PATTERN.matcher(reply);
        while (chunkIdMatcher.find()) {
            String citedId = chunkIdMatcher.group(1).trim().toLowerCase(Locale.ROOT);
            KnowledgeChunk matched = byId.get(citedId);
            if (matched != null) {
                verified.put(matched.id(), matched);
            }
        }
        for (KnowledgeChunk chunk : supplied) {
            Pattern exactIdWord = Pattern.compile("(?<![A-Za-z0-9_\\-])" + Pattern.quote(chunk.id()) + "(?![A-Za-z0-9_\\-])", Pattern.CASE_INSENSITIVE);
            if (exactIdWord.matcher(reply).find()) {
                verified.put(chunk.id(), chunk);
            }
        }

        // 2. Match explicit [Source: <doc>, Page <N>] tags where BOTH document and page number match a supplied chunk
        Matcher sourceMatcher = SOURCE_CITATION_PATTERN.matcher(reply);
        while (sourceMatcher.find()) {
            String citedDoc = sourceMatcher.group(1).trim();
            int citedStartPage = Integer.parseInt(sourceMatcher.group(2));
            int citedEndPage = sourceMatcher.group(3) != null ? Integer.parseInt(sourceMatcher.group(3)) : citedStartPage;

            for (KnowledgeChunk chunk : supplied) {
                if (documentNamesMatch(chunk.documentName(), citedDoc)
                        && chunk.startPage() == citedStartPage
                        && chunk.endPage() == citedEndPage) {
                    verified.put(chunk.id(), chunk);
                }
            }
        }

        // 3. Match prose references that name BOTH the supplied document and the exact word-bounded page/slide number
        for (KnowledgeChunk chunk : supplied) {
            if (mentionsDocumentName(reply, chunk.documentName())
                    && mentionsExactPageNumber(reply, chunk.startPage())) {
                verified.put(chunk.id(), chunk);
            }
        }

        return verified;
    }

    private static String stripHallucinatedCitations(String reply, List<KnowledgeChunk> supplied) {
        Map<String, KnowledgeChunk> byId = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : supplied) {
            byId.put(chunk.id().toLowerCase(Locale.ROOT), chunk);
        }

        // Strip unverified [ChunkID: ...]
        Matcher idMatcher = CHUNK_ID_PATTERN.matcher(reply);
        StringBuilder afterIdStrip = new StringBuilder();
        while (idMatcher.find()) {
            String citedId = idMatcher.group(1).trim().toLowerCase(Locale.ROOT);
            if (byId.containsKey(citedId)) {
                idMatcher.appendReplacement(afterIdStrip, Matcher.quoteReplacement(idMatcher.group(0)));
            } else {
                idMatcher.appendReplacement(afterIdStrip, "[Unverified ChunkID removed]");
            }
        }
        idMatcher.appendTail(afterIdStrip);

        // Strip unverified [Source: ..., Page ...]
        Matcher srcMatcher = SOURCE_CITATION_PATTERN.matcher(afterIdStrip.toString());
        StringBuilder afterSrcStrip = new StringBuilder();
        while (srcMatcher.find()) {
            String citedDoc = srcMatcher.group(1).trim();
            int startPage = Integer.parseInt(srcMatcher.group(2));
            int endPage = srcMatcher.group(3) != null ? Integer.parseInt(srcMatcher.group(3)) : startPage;
            boolean valid = supplied.stream().anyMatch(c ->
                    documentNamesMatch(c.documentName(), citedDoc)
                            && c.startPage() == startPage
                            && c.endPage() == endPage
            );
            if (valid) {
                srcMatcher.appendReplacement(afterSrcStrip, Matcher.quoteReplacement(srcMatcher.group(0)));
            } else {
                srcMatcher.appendReplacement(afterSrcStrip, "[Unverified Source removed]");
            }
        }
        srcMatcher.appendTail(afterSrcStrip);

        return afterSrcStrip.toString();
    }

    private static boolean documentNamesMatch(String suppliedDoc, String citedDoc) {
        String s = suppliedDoc.trim().toLowerCase(Locale.ROOT);
        String c = citedDoc.trim().toLowerCase(Locale.ROOT);
        return s.equals(c);
    }

    private static boolean mentionsDocumentName(String reply, String documentName) {
        Pattern docPattern = Pattern.compile("(?<![A-Za-z0-9_\\-])" + Pattern.quote(documentName) + "(?![A-Za-z0-9_\\-])", Pattern.CASE_INSENSITIVE);
        return docPattern.matcher(reply).find();
    }

    private static boolean mentionsExactPageNumber(String reply, int pageNumber) {
        // Negative lookahead (?![0-9]) prevents "Page 2" from matching "Page 23"
        Pattern pagePattern = Pattern.compile(
                "\\b(?:page|slide)s?\\s+" + pageNumber + "\\b(?![0-9])",
                Pattern.CASE_INSENSITIVE
        );
        return pagePattern.matcher(reply).find();
    }

    private static String cleanInlineChunkIdTags(String reply) {
        return reply.replaceAll("`?\\[ChunkID:\\s*[A-Za-z0-9_\\-]+\\s*\\]`?\\s*,?\\s*", "").trim();
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
