package com.adlanda.contextorchestrator.model;

import java.util.List;

/**
 * Represents a chunk of text from a source document, along with its embedding vector.
 *
 * @param id          Unique identifier for this chunk
 * @param content     The text content of the chunk
 * @param sourceFile  Path to the source file
 * @param chunkIndex  Index of this chunk within the source file
 * @param fileHash    SHA-256 hash of the source file (for change detection)
 * @param embedding   Vector representation of the content (1536 dimensions for text-embedding-3-small)
 */
public record DocumentChunk(
        String id,
        String content,
        String sourceFile,
        int chunkIndex,
        String fileHash,
        List<Double> embedding
) {
    /**
     * Creates a chunk without an embedding (before embedding is generated).
     */
    public static DocumentChunk withoutEmbedding(String id, String content, String sourceFile, int chunkIndex, String fileHash) {
        return new DocumentChunk(id, content, sourceFile, chunkIndex, fileHash, null);
    }

    /**
     * Creates a chunk without an embedding and without a file hash (backward compatibility).
     */
    public static DocumentChunk withoutEmbedding(String id, String content, String sourceFile, int chunkIndex) {
        return new DocumentChunk(id, content, sourceFile, chunkIndex, null, null);
    }

    /**
     * Creates a new chunk with the given embedding.
     */
    public DocumentChunk withEmbedding(List<Double> embedding) {
        return new DocumentChunk(id, content, sourceFile, chunkIndex, fileHash, embedding);
    }

    /**
     * Returns true if this chunk has an embedding.
     */
    public boolean hasEmbedding() {
        return embedding != null && !embedding.isEmpty();
    }
}