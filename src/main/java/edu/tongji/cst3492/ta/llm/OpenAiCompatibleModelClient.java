package edu.tongji.cst3492.ta.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * HTTP LLM client using JDK {@link HttpClient} and Jackson {@link ObjectMapper} as taught in Lecture 5 (Slide 17).
 * Supports OpenAI-compatible chat completion APIs including:
 * - Tongji University AI Services Platform (https://agi.tongji.edu.cn)
 * - DeepSeek API (https://api.deepseek.com/v1)
 */
public final class OpenAiCompatibleModelClient implements ModelClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final Duration timeout;

    public OpenAiCompatibleModelClient(String baseUrl, String apiKey, String modelName) {
        this(baseUrl, apiKey, modelName, Duration.ofSeconds(35));
    }

    public OpenAiCompatibleModelClient(String baseUrl, String apiKey, String modelName, Duration timeout) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = (modelName == null || modelName.isBlank()) ? "deepseek-chat" : modelName.trim();
        this.timeout = timeout;
        this.httpClient = SHARED_HTTP_CLIENT;
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !baseUrl.isBlank();
    }

    @Override
    public String complete(List<ChatMessage> messages) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM API key is not configured");
        }
        String endpoint = baseUrl.endsWith("/chat/completions")
                ? baseUrl
                : baseUrl + "/chat/completions";

        String payload = buildRequestBody(messages);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("LLM API HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseResponseContent(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM request interrupted", e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to call LLM endpoint: " + e.getMessage(), e);
        }
    }

    String buildRequestBody(List<ChatMessage> messages) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", 0.2);
        root.put("stream", false);

        ArrayNode msgArray = root.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode m = msgArray.addObject();
            m.put("role", msg.role());
            m.put("content", msg.content());
        }
        return root.toString();
    }

    static String parseResponseContent(String responseJson) throws IOException {
        JsonNode root = MAPPER.readTree(responseJson);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("LLM response contains no choices: " + responseJson);
        }
        JsonNode messageNode = choices.get(0).path("message");
        JsonNode contentNode = messageNode.path("content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new IOException("LLM response message has null content");
        }
        return contentNode.asText();
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "https://api.deepseek.com/v1";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Override
    public String providerName() {
        return modelName + " (" + baseUrl + ")";
    }
}
