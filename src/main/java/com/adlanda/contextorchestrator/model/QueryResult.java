package com.adlanda.contextorchestrator.model;

/**
 * A single result from a query, containing the matched chunk and its similarity score.
 *
 * @param content     The text content of the matched chunk
 * @param sourceFile  Path to the source file
 * @param chunkIndex  Index of this chunk within the source file
 * @param score       Cosine similarity score (0.0 to 1.0, higher is more similar)
 */
public record QueryResult(
        String content,
        String sourceFile,
        int chunkIndex,
        double score
) {
    public static QueryResult from(DocumentChunk chunk, double score) {
        return new QueryResult(chunk.content(), chunk.sourceFile(), chunk.chunkIndex(), score);
    }
}
