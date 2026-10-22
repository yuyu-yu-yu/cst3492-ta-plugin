package edu.tongji.cst3492.ta.llm;

import java.util.Objects;

/**
 * Immutable chat message following the role/content structure from Lecture 5 (Slide 14-15).
 */
public record ChatMessage(String role, String content) {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public ChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ASSISTANT, content);
    }
}
