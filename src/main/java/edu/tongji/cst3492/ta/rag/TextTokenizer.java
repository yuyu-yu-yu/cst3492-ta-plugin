package edu.tongji.cst3492.ta.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes and tokenizes English and technical Java identifiers for RAG indexing and retrieval.
 * Handles CamelCase splitting (e.g., ConcurrentModificationException -> concurrent, modification, exception,
 * concurrentmodificationexception) so both exact symbol names and natural language queries match.
 */
public final class TextTokenizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*|[0-9]+|[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have",
            "in", "is", "it", "its", "of", "on", "or", "that", "the", "their", "there",
            "these", "this", "to", "was", "were", "will", "with", "what", "when", "where",
            "which", "who", "how", "why", "can", "could", "should", "would", "do", "does",
            "explain", "work", "works", "use", "using", "used", "please", "about",
            "java", "enterprise", "application", "applications", "development", "course",
            "lecture", "slide", "slides", "cst3492"
    );

    private TextTokenizer() {}

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.length() > 1 && !STOP_WORDS.contains(lower)) {
                tokens.add(lower);
            }
            // Split CamelCase or snake_case technical terms
            if (raw.contains("_") || hasInternalUpperCase(raw)) {
                String[] parts = raw.replace('_', ' ').split("\\s+");
                for (String part : parts) {
                    String partLower = part.toLowerCase(Locale.ROOT);
                    if (partLower.length() > 1 && !STOP_WORDS.contains(partLower) && !partLower.equals(lower)) {
                        tokens.add(partLower);
                    }
                    String[] camelParts = CAMEL_SPLIT.split(part);
                    if (camelParts.length > 1) {
                        for (String sub : camelParts) {
                            String subLower = sub.toLowerCase(Locale.ROOT);
                            if (subLower.length() > 1 && !STOP_WORDS.contains(subLower)
                                    && !subLower.equals(lower) && !subLower.equals(partLower)) {
                                tokens.add(subLower);
                            }
                        }
                    }
                }
            }
        }
        return tokens;
    }

    public static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokenize(text)) {
            counts.merge(token, 1, Integer::sum);
        }
        return counts;
    }

    private static boolean hasInternalUpperCase(String word) {
        for (int i = 1; i < word.length(); i++) {
            if (Character.isUpperCase(word.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
