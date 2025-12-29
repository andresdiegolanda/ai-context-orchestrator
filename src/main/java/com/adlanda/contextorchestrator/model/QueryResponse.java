package com.adlanda.contextorchestrator.model;

import java.util.List;

/**
 * Response from the query endpoint.
 *
 * @param results      List of matched chunks, ordered by relevance
 * @param totalChunks  Total number of chunks in the index
 * @param queryTimeMs  Time taken to process the query in milliseconds
 */
public record QueryResponse(
        List<QueryResult> results,
        int totalChunks,
        long queryTimeMs
) {}
