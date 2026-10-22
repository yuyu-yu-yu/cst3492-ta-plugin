package edu.tongji.cst3492.ta.llm;

import edu.tongji.cst3492.ta.PluginConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic local Course TA client used for offline unit testing and live classroom demonstrations
 * when no external API key is configured or when the network is unavailable.
 * Synthesizes grounded responses directly from the retrieved course chunks and editor code context,
 * citing exact ChunkIDs, document names, and page numbers, or stating the general-knowledge fallback notice.
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
        sb.append("### CST3492 Course TA Explanation\n\n");

        if (!codeContext.isBlank()) {
            sb.append("**Selected Code Analysis:**\n");
            sb.append(analyzeSelectedCode(codeContext, question)).append("\n\n");
        }

        sb.append("**Course Material Synthesis:**\n");
        for (int i = 0; i < Math.min(2, chunks.size()); i++) {
            ParsedChunk c = chunks.get(i);
            String excerpt = summarizeExcerpt(c.content());
            sb.append("- **").append(c.sectionTitle()).append("** ")
                    .append("(`[ChunkID: ").append(c.id()).append("]`, ")
                    .append("`[Source: ").append(c.source()).append(", ").append(c.pageScope()).append("]`):\n  ")
                    .append(excerpt).append("\n");
        }

        ParsedChunk primary = chunks.get(0);
        sb.append("\n**Key Takeaway for Your Question:**\n");
        sb.append(synthesizeCourseTakeaway(question, primary));

        return sb.toString().trim();
    }

    private static String analyzeSelectedCode(String code, String question) {
        String lowerCode = code.toLowerCase(Locale.ROOT);
        List<String> observations = new ArrayList<>();
        if (lowerCode.contains("httpclient") || lowerCode.contains("httprequest")) {
            observations.add("This code uses JDK `HttpClient` to construct and send an HTTP request (covered in Lecture 5 Slide 17 on calling LLM APIs with explicit timeouts and Bearer headers).");
        }
        if (lowerCode.contains("record ")) {
            observations.add("It defines an immutable Java `record` for structured data contracts (covered in Lecture 5 Slide 20 on `Feedback` records and compact constructors).");
        }
        if (lowerCode.contains("executorservice") || lowerCode.contains("future")) {
            observations.add("It manages concurrent execution with `ExecutorService` / `Future` timeouts so slow tasks do not block the application (covered in Lecture 5 Slide 37).");
        }
        if (lowerCode.contains("map") || lowerCode.contains("hashmap") || lowerCode.contains("list")) {
            observations.add("It operates on Java Collections (`Map` / `List`). Be mindful of null keys/values and avoiding `ConcurrentModificationException` when mutating during iteration.");
        }
        if (observations.isEmpty()) {
            observations.add("Examining the selected snippet (`" + firstLinePreview(code) + "`) in relation to your question: *" + question + "*.");
        }
        return String.join(" ", observations);
    }

    private static String synthesizeCourseTakeaway(String question, ParsedChunk primary) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("rrf") || q.contains("reciprocal") || q.contains("hybrid")) {
            return "In CST3492 Lecture 5, **Hybrid Retrieval** combines **Keyword Search (BM25)** (which excels at exact identifier matches) and **Vector Search (Cosine Similarity)** (which captures semantic meaning) using **Reciprocal Rank Fusion (RRF)**: `score(p) = sum(1 / (60 + rank))` because BM25 and cosine similarity operate on different numerical scales (`[Source: " + primary.source() + ", " + primary.pageScope() + "]`).";
        }
        if (q.contains("rag") || q.contains("cite") || q.contains("citation")) {
            return "Our course RAG pipeline follows four steps: **1. Chunk** course notes into passages with IDs, **2. Index** via keyword + vector indexes, **3. Retrieve** top passages per query, and **4. Generate** a response where the model cites supplied passage IDs and Java verifies every cited ID was actually supplied (`[Source: " + primary.source() + ", " + primary.pageScope() + "]`).";
        }
        if (q.contains("tool") || q.contains("loop") || q.contains("agent")) {
            return "As shown in the course slides, an AI tool is a read-only Java method dispatched by `ToolDispatcher` after checking the allow-list, and the tool loop is strictly bounded (`MAX_TOOL_ROUNDS = 3`) to prevent infinite API calls (`[Source: " + primary.source() + ", " + primary.pageScope() + "]`).";
        }
        return "According to **" + primary.source() + " (" + primary.pageScope() + ")**, Java remains in control of ground-truth data, context retrieval, and validation while the LLM proposes drafts or explanations (`[Source: " + primary.source() + ", " + primary.pageScope() + "]`).";
    }

    private static String synthesizeGeneralJavaAnswer(String question, String codeContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("### General Java Programming Guidance\n\n");
        if (!codeContext.isBlank()) {
            sb.append("Regarding your selected code (`").append(firstLinePreview(codeContext)).append("`) and question (*")
                    .append(question).append("*):\n\n");
            sb.append("- Ensure clear separation of concerns, immutable data carriers (`record` where appropriate), and explicit null/boundary checks.\n");
            sb.append("- Add targeted JUnit 5 tests covering normal execution, boundary inputs, and expected exceptions.\n");
        } else {
            sb.append("For your question (*").append(question).append("*):\n\n");
            sb.append("No matching CST3492 lecture slide was found in the indexed knowledge base for this topic. ")
                    .append("From general Java enterprise best practices, prefer standard library APIs, explicit error handling, and verifiable unit tests.");
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
        return "CST3492 Built-in Course TA Engine (Offline/Demo Ready)";
    }

    private record ParsedChunk(String id, String source, String pageScope, String sectionTitle, String content) {}
}
