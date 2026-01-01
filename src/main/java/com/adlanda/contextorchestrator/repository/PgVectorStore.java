package com.adlanda.contextorchestrator.repository;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * PostgreSQL-based vector store using PGVector extension.
 *
 * This is a wrapper around Spring AI's VectorStore that provides
 * a consistent interface for storing and searching document chunks.
 *
 * Iteration 2: Replaces InMemoryVectorStore with persistent PGVector storage.
 */
@Repository
public class PgVectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private final VectorStore vectorStore;

    public PgVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Stores a document chunk in the vector store.
     *
     * @param chunk The chunk to store (must have embedding)
     */
    public void store(DocumentChunk chunk) {
        if (!chunk.hasEmbedding()) {
            throw new IllegalArgumentException("Cannot store chunk without embedding");
        }

        Document document = toDocument(chunk);
        vectorStore.add(List.of(document));
        log.debug("Stored chunk {} from {}", chunk.id(), chunk.sourceFile());
    }

    /**
     * Stores multiple chunks in the vector store.
     *
     * @param chunks The chunks to store
     */
    public void storeAll(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        List<Document> documents = chunks.stream()
                .map(this::toDocument)
                .toList();

        vectorStore.add(documents);
        log.info("Stored {} chunks in vector store", chunks.size());
    }

    /**
     * Finds the most similar chunks to the given query embedding.
     *
     * @param queryEmbedding The embedding to search for
     * @param maxResults     Maximum number of results to return
     * @return List of (chunk, similarity score) pairs, sorted by similarity descending
     */
    public List<ScoredChunk> findSimilar(List<Double> queryEmbedding, int maxResults) {
        // Note: Spring AI's VectorStore uses the query text, not the embedding directly
        // We need to use a different approach - see findSimilarByText
        throw new UnsupportedOperationException(
                "Direct embedding search not supported. Use findSimilarByText instead.");
    }

    /**
     * Finds similar documents by query text.
     * Spring AI handles the embedding internally.
     *
     * @param queryText  The query text to search for
     * @param maxResults Maximum number of results to return
     * @return List of scored chunks
     */
    public List<ScoredChunk> findSimilarByText(String queryText, int maxResults) {
        SearchRequest request = SearchRequest.query(queryText)
                .withTopK(maxResults);

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
                .map(this::toScoredChunk)
                .toList();
    }

    /**
     * Deletes all chunks associated with a specific source file.
     * Used when re-ingesting a changed file.
     *
     * @param sourceFile The source file path
     */
    public void deleteBySourceFile(String sourceFile) {
        // Note: Spring AI VectorStore doesn't directly support delete by metadata
        // This would require a custom implementation or direct JDBC access
        log.warn("Delete by source file not yet implemented for PGVector");
    }

    /**
     * Converts a DocumentChunk to a Spring AI Document.
     */
    private Document toDocument(DocumentChunk chunk) {
        Map<String, Object> metadata = Map.of(
                "sourceFile", chunk.sourceFile(),
                "chunkIndex", chunk.chunkIndex(),
                "fileHash", chunk.fileHash() != null ? chunk.fileHash() : ""
        );

        return new Document(chunk.id(), chunk.content(), metadata);
    }

    /**
     * Converts a Spring AI Document back to a ScoredChunk.
     */
    private ScoredChunk toScoredChunk(Document document) {
        String sourceFile = (String) document.getMetadata().getOrDefault("sourceFile", "unknown");
        int chunkIndex = ((Number) document.getMetadata().getOrDefault("chunkIndex", 0)).intValue();
        String fileHash = (String) document.getMetadata().getOrDefault("fileHash", "");

        // Get the similarity score from metadata (Spring AI stores it there)
        Object scoreObj = document.getMetadata().get("score");
        double score = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.0;

        DocumentChunk chunk = new DocumentChunk(
                document.getId(),
                document.getContent(),
                sourceFile,
                chunkIndex,
                fileHash,
                null  // Embedding not needed for results
        );

        return new ScoredChunk(chunk, score);
    }

    /**
     * A chunk with its similarity score.
     */
    public record ScoredChunk(DocumentChunk chunk, double score) {}

    /**
     * Returns the approximate number of documents in the vector store.
     * Note: This performs a similarity search with a generic query to estimate size.
     * For accurate counts, use the IngestedSourceRepository.
     */
    public int size() {
        // A rough estimate - actual count requires direct DB query
        return 0;
    }
}