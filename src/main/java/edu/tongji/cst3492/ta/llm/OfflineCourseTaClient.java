package edu.tongji.cst3492.ta.llm;

import edu.tongji.cst3492.ta.PluginConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic offline Course TA client for unit testing and offline demonstrations.
 * Synthesizes responses strictly from the supplied {@code [ChunkID: ...]} passages in the prompt,
 * never referencing unretrieved slide numbers or unverified facts.
 */
public final class OfflineCourseTaClient implements ModelClient {

    private static final Pattern CHUNK_BLOCK_PATTERN = Pattern.compile(
            "\\[ChunkID:\\s*([^|\\]]+)\\|\\s*Source:\\s*([^|\\]]+)\\|\\s*([^|\\]]+)\\|\\s*Section:\\s*([^\\]]+)\\]\\s*([\\s\\S]*?)(?=\\n\\[ChunkID:|\\n=== STUDENT QUESTION ===)"
    );

    @Override
    public String complete(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return PluginConstants.GENERAL_KNOWLEDGE_FALLBACK;
        }
        String lastUserPrompt = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (ChatMessage.USER.equals(messages.get(i).role())) {
                lastUserPrompt = messages.get(i).content();
                break;
            }
        }

        String question = extractSection(lastUserPrompt, "=== STUDENT QUESTION ===");
        String codeContext = extractCodeBlock(lastUserPrompt);
        List<ParsedChunk> chunks = parseSuppliedChunks(lastUserPrompt);

        if (chunks.isEmpty()) {
            return synthesizeGeneralJavaAnswer(question, codeContext)
                    + "\n\n" + PluginConstants.GENERAL_KNOWLEDGE_FALLBACK;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### CST3492 Course TA Explanation (Offline Demo Mode)\n\n");

        if (!codeContext.isBlank()) {
            ParsedChunk primary = chunks.get(0);
            sb.append("**Selected Code Context Analysis:**\n");
            sb.append("Analyzing your selected snippet (`").append(firstLinePreview(codeContext))
                    .append("`) against retrieved course material ")
                    .append("[ChunkID: ").append(primary.id()).append("] ")
                    .append("[Source: ").append(primary.source()).append(", ").append(primary.pageScope()).append("].\n\n");
        }

        sb.append("**Relevant Course Material Passages:**\n");
        for (int i = 0; i < Math.min(2, chunks.size()); i++) {
            ParsedChunk c = chunks.get(i);
            String excerpt = summarizeExcerpt(c.content());
            sb.append("- **").append(c.sectionTitle()).append("** ")
                    .append("[ChunkID: ").append(c.id()).append("] ")
                    .append("[Source: ").append(c.source()).append(", ").append(c.pageScope()).append("]:\n  ")
                    .append(excerpt).append("\n");
        }

        ParsedChunk primary = chunks.get(0);
        sb.append("\n**Summary:**\n");
        sb.append("Based on **").append(primary.sectionTitle()).append("** in ")
                .append("[Source: ").append(primary.source()).append(", ").append(primary.pageScope()).append("], ")
                .append(summarizeExcerpt(primary.content()));

        return sb.toString().trim();
    }

    private static String synthesizeGeneralJavaAnswer(String question, String codeContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("### General Java Programming Guidance\n\n");
        if (!codeContext.isBlank()) {
            sb.append("Regarding your selected code (`").append(firstLinePreview(codeContext)).append("`) and question (*")
                    .append(question).append("*):\n\n");
            sb.append("- Verify boundary conditions, null safety, and immutability of data carriers.\n");
            sb.append("- Add unit tests covering normal execution and edge cases.\n");
        } else {
            sb.append("Regarding your question (*").append(question).append("*):\n\n");
            sb.append("No matching CST3492 course slide was retrieved for this specific topic. ")
                    .append("From general Java programming knowledge, follow standard JDK idioms, explicit error handling, and unit testing.");
        }
        return sb.toString();
    }

    private static List<ParsedChunk> parseSuppliedChunks(String prompt) {
        List<ParsedChunk> list = new ArrayList<>();
        Matcher matcher = CHUNK_BLOCK_PATTERN.matcher(prompt);
        while (matcher.find()) {
            list.add(new ParsedChunk(
                    matcher.group(1).trim(),
                    matcher.group(2).trim(),
                    matcher.group(3).trim(),
                    matcher.group(4).trim(),
                    matcher.group(5).trim()
            ));
        }
        return list;
    }

    private static String extractSection(String text, String header) {
        int idx = text.indexOf(header);
        if (idx < 0) {
            return text.trim();
        }
        return text.substring(idx + header.length()).trim();
    }

    private static String extractCodeBlock(String text) {
        int start = text.indexOf("```java");
        if (start < 0) {
            return "";
        }
        int end = text.indexOf("```", start + 7);
        if (end < 0) {
            return "";
        }
        return text.substring(start + 7, end).trim();
    }

    private static String summarizeExcerpt(String content) {
        String singleLine = content.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 237) + "...";
    }

    private static String firstLinePreview(String code) {
        String[] lines = code.trim().split("\\R");
        String first = lines.length > 0 ? lines[0].trim() : "";
        return first.length() <= 60 ? first : first.substring(0, 57) + "...";
    }

    @Override
    public String providerName() {
        return "CST3492 Offline Demo TA (Deterministic Local Synthesis)";
    }

    private record ParsedChunk(String id, String source, String pageScope, String sectionTitle, String content) {}
}
