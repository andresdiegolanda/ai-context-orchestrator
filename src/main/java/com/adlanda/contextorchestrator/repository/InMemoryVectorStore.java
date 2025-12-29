package com.adlanda.contextorchestrator.repository;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory vector store for Iteration 1.
 *
 * Stores document chunks and their embeddings in memory.
 * Will be replaced with PGVector in Iteration 2.
 */
@Repository
public class InMemoryVectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    private final Map<String, DocumentChunk> chunks = new ConcurrentHashMap<>();

    /**
     * Stores a chunk in the vector store.
     */
    public void store(DocumentChunk chunk) {
        if (!chunk.hasEmbedding()) {
            throw new IllegalArgumentException("Cannot store chunk without embedding");
        }
        chunks.put(chunk.id(), chunk);
    }

    /**
     * Stores multiple chunks in the vector store.
     */
    public void storeAll(List<DocumentChunk> chunksToStore) {
        chunksToStore.forEach(this::store);
        log.info("Stored {} chunks in vector store", chunksToStore.size());
    }

    /**
     * Finds the most similar chunks to the given query embedding.
     *
     * @param queryEmbedding The embedding to search for
     * @param maxResults     Maximum number of results to return
     * @return List of (chunk, similarity score) pairs, sorted by similarity descending
     */
    public List<ScoredChunk> findSimilar(List<Double> queryEmbedding, int maxResults) {
        return chunks.values().stream()
                .filter(DocumentChunk::hasEmbedding)
                .map(chunk -> new ScoredChunk(chunk, cosineSimilarity(queryEmbedding, chunk.embedding())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(maxResults)
                .toList();
    }

    /**
     * Returns the total number of chunks stored.
     */
    public int size() {
        return chunks.size();
    }

    /**
     * Clears all chunks from the store.
     */
    public void clear() {
        chunks.clear();
    }

    /**
     * Computes cosine similarity between two vectors.
     *
     * @return Similarity score between 0 and 1 (1 = identical)
     */
    private double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("Vectors must have same dimension");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.size(); i++) {
            dotProduct += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * A chunk with its similarity score.
     */
    public record ScoredChunk(DocumentChunk chunk, double score) {}
}
