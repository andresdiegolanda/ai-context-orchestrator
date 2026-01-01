package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.model.QueryResponse;
import com.adlanda.contextorchestrator.model.QueryResult;
import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import com.adlanda.contextorchestrator.repository.PgVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for retrieving relevant context based on a query.
 *
 * Orchestrates the query flow:
 * 1. Search for similar chunks using PGVector
 * 2. Return ranked results
 *
 * Iteration 2: Uses PGVector for persistent storage instead of in-memory store.
 * The VectorStore handles embedding internally, so we don't need EmbeddingService here.
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final PgVectorStore vectorStore;
    private final IngestedSourceRepository ingestedSourceRepository;

    public RetrievalService(PgVectorStore vectorStore, IngestedSourceRepository ingestedSourceRepository) {
        this.vectorStore = vectorStore;
        this.ingestedSourceRepository = ingestedSourceRepository;
    }

    /**
     * Queries the vector store for relevant context.
     *
     * @param question   The question to search for
     * @param maxResults Maximum number of results to return
     * @return QueryResponse containing the matched chunks and metadata
     */
    public QueryResponse query(String question, int maxResults) {
        long startTime = System.currentTimeMillis();

        // Search for similar chunks (Spring AI VectorStore handles embedding internally)
        List<PgVectorStore.ScoredChunk> scoredChunks = vectorStore.findSimilarByText(question, maxResults);

        // Convert to results
        List<QueryResult> results = scoredChunks.stream()
                .map(sc -> QueryResult.from(sc.chunk(), sc.score()))
                .toList();

        long queryTimeMs = System.currentTimeMillis() - startTime;

        if (log.isDebugEnabled()) {
            log.debug("Query '{}' returned {} results in {}ms",
                    truncate(question, 50), results.size(), queryTimeMs);
        }

        return new QueryResponse(results, (int) getIndexSize(), queryTimeMs);
    }

    /**
     * Returns the total number of chunks across all ingested files.
     */
    public long getIndexSize() {
        return ingestedSourceRepository.sumChunkCount();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}