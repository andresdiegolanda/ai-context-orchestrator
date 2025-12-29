package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.model.QueryResponse;
import com.adlanda.contextorchestrator.model.QueryResult;
import com.adlanda.contextorchestrator.repository.InMemoryVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for retrieving relevant context based on a query.
 *
 * Orchestrates the query flow:
 * 1. Embed the query
 * 2. Search for similar chunks
 * 3. Return ranked results
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final EmbeddingService embeddingService;
    private final InMemoryVectorStore vectorStore;

    public RetrievalService(EmbeddingService embeddingService, InMemoryVectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
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

        // 1. Embed the query
        List<Double> queryEmbedding = embeddingService.embed(question);

        // 2. Search for similar chunks
        List<InMemoryVectorStore.ScoredChunk> scoredChunks = vectorStore.findSimilar(queryEmbedding, maxResults);

        // 3. Convert to results
        List<QueryResult> results = scoredChunks.stream()
                .map(sc -> QueryResult.from(sc.chunk(), sc.score()))
                .toList();

        long queryTimeMs = System.currentTimeMillis() - startTime;

        log.debug("Query '{}' returned {} results in {}ms",
                truncate(question, 50), results.size(), queryTimeMs);

        return new QueryResponse(results, vectorStore.size(), queryTimeMs);
    }

    /**
     * Returns the number of chunks in the index.
     */
    public int getIndexSize() {
        return vectorStore.size();
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
