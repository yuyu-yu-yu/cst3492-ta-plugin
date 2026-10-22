package edu.tongji.cst3492.ta.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverted index using Okapi BM25 ranking over {@link KnowledgeChunk}s.
 * Implements the three BM25 principles taught in Lecture 5 (Slide 24):
 * 1. Rare words count more (IDF)
 * 2. Repeating a word helps with diminishing returns (k1 saturation)
 * 3. Long passages count each word less (length normalization b)
 */
public final class KeywordIndex {

    private static final double DEFAULT_K1 = 1.5;
    private static final double DEFAULT_B = 0.75;

    private final List<KnowledgeChunk> chunks;
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
    private final int[] docLengths;
    private final double avgDocLength;

    public KeywordIndex(List<KnowledgeChunk> chunks) {
        this.chunks = List.copyOf(chunks);
        this.docLengths = new int[this.chunks.size()];
        long totalTokens = 0;

        for (int docIdx = 0; docIdx < this.chunks.size(); docIdx++) {
            KnowledgeChunk chunk = this.chunks.get(docIdx);
            String fullText = chunk.sectionTitle() + " " + chunk.content();
            List<String> tokens = TextTokenizer.tokenize(fullText);
            docLengths[docIdx] = tokens.size();
            totalTokens += tokens.size();

            for (String token : tokens) {
                postings.computeIfAbsent(token, k -> new LinkedHashMap<>())
                        .merge(docIdx, 1, Integer::sum);
            }
        }
        this.avgDocLength = this.chunks.isEmpty() ? 1.0 : Math.max(1.0, (double) totalTokens / this.chunks.size());
    }

    public List<ScoredChunk> search(String query, int topK) {
        if (query == null || query.isBlank() || chunks.isEmpty() || topK <= 0) {
            return List.of();
        }
        Map<String, Integer> queryTerms = TextTokenizer.termFrequencies(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        double[] scores = new double[chunks.size()];
        int[] matchedDistinctTerms = new int[chunks.size()];
        int totalDocs = chunks.size();

        for (Map.Entry<String, Integer> entry : queryTerms.entrySet()) {
            String term = entry.getKey();
            Map<Integer, Integer> termPostings = postings.get(term);
            if (termPostings == null || termPostings.isEmpty()) {
                continue;
            }
            int docFreq = termPostings.size();
            double idf = Math.log(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));

            for (Map.Entry<Integer, Integer> posting : termPostings.entrySet()) {
                int docIdx = posting.getKey();
                int tf = posting.getValue();
                double normLength = docLengths[docIdx] / avgDocLength;
                double numerator = tf * (DEFAULT_K1 + 1.0);
                double denominator = tf + DEFAULT_K1 * (1.0 - DEFAULT_B + DEFAULT_B * normLength);
                scores[docIdx] += idf * (numerator / denominator);
                matchedDistinctTerms[docIdx]++;
            }
        }

        int minRequiredTerms = queryTerms.size() >= 3 ? 2 : 1;
        List<ScoredChunk> results = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (scores[i] > 0.0 && (matchedDistinctTerms[i] >= minRequiredTerms || scores[i] >= 4.5)) {
                results.add(new ScoredChunk(chunks.get(i), scores[i], scores[i], 0.0));
            }
        }
        Collections.sort(results);
        return results.size() <= topK ? results : List.copyOf(results.subList(0, topK));
    }

    public int size() {
        return chunks.size();
    }
}
