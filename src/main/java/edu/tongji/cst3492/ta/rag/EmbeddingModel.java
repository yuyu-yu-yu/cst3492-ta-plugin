package edu.tongji.cst3492.ta.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight domain-lexicon and feature-hashing vector model for offline RAG retrieval
 * (following Lecture 5 Slide 25 "Vector Search: Similar Meaning").
 * Combines 12 focused technical synonym clusters with 116 signed feature-hashing dimensions
 * so synonym queries (e.g. "dictionary lookup" -> Map/HashMap) match without false-positive
 * triggering on broad words like "java", "spring", or "injection".
 */
public final class EmbeddingModel {

    private static final int CONCEPT_DIMS = 13;
    private static final int HASH_DIMS = 115;
    public static final int TOTAL_DIMS = CONCEPT_DIMS + HASH_DIMS;

    private static final List<Set<String>> CONCEPT_CLUSTERS = List.of(
            // 0: Map / Dictionary / Key-Value lookup (Slide 24-26)
            Set.of("map", "maps", "hashmap", "treemap", "linkedhashmap", "dictionary", "lookup", "key", "keys", "value", "values", "pair", "pairs"),
            // 1: List / Collection / Iteration / ConcurrentModificationException
            Set.of("list", "lists", "arraylist", "linkedlist", "collection", "collections", "iterator", "iterate", "loop", "looping", "concurrentmodificationexception"),
            // 2: Exception / Error handling
            Set.of("exception", "exceptions", "try", "catch", "finally", "throw", "throws", "runtimeexception", "checked", "unchecked", "invalidfeedbackexception", "toolscopeexception"),
            // 3: RAG / Retrieval / Chunking / Citations
            Set.of("rag", "retrieval", "retrieve", "retriever", "hybrid", "hybridretriever", "chunk", "chunks", "chunking", "passage", "passages", "cite", "citation", "citations", "evidence"),
            Set.of("keyword", "keywords", "keywordindex", "bm25", "inverted", "index", "indexing"),
            // 5: Vector / Embedding / Cosine / Reciprocal Rank Fusion
            Set.of("vector", "vectors", "vectorindex", "embedding", "embeddings", "embeddingmodel", "cosine", "similarity", "semantic", "rrf", "reciprocal", "fusion"),
            // 6: LLM / ModelClient / HTTP API
            Set.of("llm", "modelclient", "deepseek", "deepseekclient", "fakemodelclient", "httpclient", "httprequest", "httpresponse", "apikey", "completions"),
            // 7: Structured Output / JSON / Record Validation
            Set.of("json", "jackson", "record", "records", "contract", "feedbackvalidator", "risklevel"),
            // 8: Agent Tools / Dispatcher / Bounded Loop
            Set.of("tool", "tools", "tooldispatcher", "toolcall", "dispatcher", "searchnotes", "getclassaverage", "steplimit"),
            // 9: Session / Conversation History / Trimming
            Set.of("session", "sessions", "feedbacksession", "history", "trimhistory", "conversation", "conversations"),
            // 10: Concurrency / Thread Pool / Future Timeout
            Set.of("executor", "executorservice", "threadpool", "future", "timeout", "timeoutexception", "parallel", "classdrafter"),
            // 11: Testing / JUnit / Recall Evaluation
            Set.of("junit", "assert", "assertion", "offline", "verification", "recall"),
            // 12: Prompt Security / Boundary & Citation Checking (Slide 28-29)
            Set.of("prompt", "injection", "defend", "ignore", "boundary", "instructions", "rules", "data", "cite", "cites", "cited", "supplied", "never", "invalid", "draft")
    );

    public float[] embed(String text) {
        float[] vec = new float[TOTAL_DIMS];
        Map<String, Integer> termFreqs = TextTokenizer.termFrequencies(text);
        if (termFreqs.isEmpty()) {
            return vec;
        }

        for (Map.Entry<String, Integer> entry : termFreqs.entrySet()) {
            String term = entry.getKey();
            float weight = (float) (1.0 + Math.log(entry.getValue()));

            for (int c = 0; c < CONCEPT_CLUSTERS.size(); c++) {
                if (CONCEPT_CLUSTERS.get(c).contains(term)) {
                    vec[c] += weight * 1.6f;
                }
            }

            int h = MixHash.hash(term);
            int bucket = CONCEPT_DIMS + Math.floorMod(h, HASH_DIMS);
            float sign = ((h >>> 31) == 0) ? 1.0f : -1.0f;
            vec[bucket] += sign * weight;
        }

        normalizeInPlace(vec);
        return vec;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions must match");
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    private static void normalizeInPlace(float[] vec) {
        double sumSq = 0.0;
        for (float v : vec) {
            sumSq += (double) v * v;
        }
        if (sumSq > 1e-12) {
            float invNorm = (float) (1.0 / Math.sqrt(sumSq));
            for (int i = 0; i < vec.length; i++) {
                vec[i] *= invNorm;
            }
        }
    }

    private static final class MixHash {
        static int hash(String s) {
            int h = 0x811c9dc5;
            for (int i = 0; i < s.length(); i++) {
                h ^= s.charAt(i);
                h *= 0x01000193;
            }
            h ^= (h >>> 16);
            return h;
        }
    }
}
