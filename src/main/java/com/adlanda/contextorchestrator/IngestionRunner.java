package com.adlanda.contextorchestrator;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import com.adlanda.contextorchestrator.repository.InMemoryVectorStore;
import com.adlanda.contextorchestrator.service.EmbeddingService;
import com.adlanda.contextorchestrator.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs document ingestion on application startup.
 *
 * Reads all documents from the configured docs directory,
 * generates embeddings, and stores them in the vector store.
 */
@Component
@Order(1) // Run before StartupInfoLogger
public class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final IngestionService ingestionService;
    private final EmbeddingService embeddingService;
    private final InMemoryVectorStore vectorStore;

    public IngestionRunner(IngestionService ingestionService,
                          EmbeddingService embeddingService,
                          InMemoryVectorStore vectorStore) {
        this.ingestionService = ingestionService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting document ingestion...");

        try {
            // 1. Read and chunk documents
            List<DocumentChunk> chunks = ingestionService.ingestAllDocuments();

            if (chunks.isEmpty()) {
                log.warn("No documents found to ingest");
                return;
            }

            // 2. Generate embeddings
            List<DocumentChunk> embeddedChunks = embeddingService.embedChunks(chunks);

            // 3. Store in vector store
            vectorStore.storeAll(embeddedChunks);

            log.info("Ingestion complete: {} chunks indexed", vectorStore.size());

        } catch (Exception e) {
            log.error("Failed to ingest documents: {}", e.getMessage(), e);
        }
    }
}
