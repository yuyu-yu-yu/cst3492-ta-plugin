package edu.tongji.cst3492.ta.rag;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic embedding model for course RAG retrieval.
 * Combines semantic concept dimensions (synonym clusters for Java enterprise, RAG, LLM, testing,
 * collections, exceptions, concurrency) with hashed token projections so semantic queries
 * (e.g. "dictionary lookup" -> Map/HashMap, "cite slide source" -> RAG/evidence) match even when exact words differ.
 */
public final class EmbeddingModel {

    private static final int CONCEPT_DIMS = 16;
    private static final int HASH_DIMS = 112;
    public static final int TOTAL_DIMS = CONCEPT_DIMS + HASH_DIMS;

    private static final List<Set<String>> CONCEPT_CLUSTERS = List.of(
            Set.of("map", "maps", "hashmap", "treemap", "linkedhashmap", "dictionary", "lookup", "key", "keys", "value", "values", "pair", "pairs", "put", "get"),
            Set.of("list", "lists", "arraylist", "linkedlist", "collection", "collections", "iterator", "iterate", "loop", "looping", "remove", "removing", "concurrentmodificationexception"),
            Set.of("exception", "exceptions", "error", "errors", "try", "catch", "finally", "throw", "throws", "runtimeexception", "checked", "unchecked", "invalidfeedbackexception", "toolscopeexception"),
            Set.of("rag", "retrieval", "retrieve", "retriever", "hybrid", "hybridretriever", "chunk", "chunks", "chunking", "passage", "passages", "cite", "citation", "citations", "source", "sources", "evidence", "notes", "coursenotes"),
            Set.of("keyword", "keywords", "keywordindex", "bm25", "inverted", "index", "indexing", "term", "frequency", "rare", "exact"),
            Set.of("vector", "vectors", "vectorindex", "embedding", "embeddings", "embeddingmodel", "cosine", "similarity", "semantic", "meaning", "rrf", "reciprocal", "rank", "fusion", "bge", "milvus", "pgvector"),
            Set.of("llm", "model", "models", "modelclient", "deepseek", "deepseekclient", "fakemodelclient", "openai", "http", "httpclient", "request", "response", "api", "apikey", "token", "tokens", "prompt", "system", "assistant"),
            Set.of("json", "jackson", "record", "records", "contract", "structure", "validate", "validator", "feedback", "feedbackvalidator", "risk", "risklevel", "low", "medium", "high", "draft", "invalid"),
            Set.of("tool", "tools", "tooldispatcher", "toolcall", "dispatch", "dispatcher", "function", "arguments", "searchnotes", "getclassaverage", "loop", "round", "rounds", "steplimit", "limit", "bounded"),
            Set.of("session", "sessions", "feedbacksession", "history", "memory", "trim", "trimhistory", "conversation", "conversations", "exchange", "context", "window"),
            Set.of("thread", "threads", "executor", "executorservice", "threadpool", "future", "timeout", "timeoutexception", "parallel", "classdrafter", "async", "cancel", "wait"),
            Set.of("test", "tests", "testing", "junit", "assert", "assertion", "offline", "fake", "mock", "verify", "verification", "recall", "measure", "rubric"),
            Set.of("security", "injection", "ignore", "boundary", "instructions", "data", "enforce", "enforced", "rule", "rules", "teacher", "review"),
            Set.of("intellij", "idea", "plugin", "ide", "editor", "action", "toolwindow", "sidebar", "selection", "contextmenu", "jetbrains", "sdk"),
            Set.of("pdf", "pdfbox", "tika", "document", "documents", "slide", "slides", "page", "pages", "parse", "parser"),
            Set.of("java", "enterprise", "spring", "gradebook", "student", "students", "score", "scores", "average", "course", "cst3492")
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
                    vec[c] += weight * 2.2f;
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
