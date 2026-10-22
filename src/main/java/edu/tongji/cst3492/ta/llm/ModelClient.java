package edu.tongji.cst3492.ta.llm;

import java.util.List;

/**
 * Common interface for LLM providers (Live HTTP OpenAI/DeepSeek/Tongji client and Offline/Test client).
 * Follows the "One Interface, Two Model Clients" design in Lecture 5 (Slide 39).
 */
public interface ModelClient {

    String complete(List<ChatMessage> messages);

    String providerName();
}
