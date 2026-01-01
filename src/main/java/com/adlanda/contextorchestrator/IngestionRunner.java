package com.adlanda.contextorchestrator;

import com.adlanda.contextorchestrator.config.IngestionProperties;
import com.adlanda.contextorchestrator.health.IngestionHealthIndicator;
import com.adlanda.contextorchestrator.model.DocumentChunk;
import com.adlanda.contextorchestrator.repository.PgVectorStore;
import com.adlanda.contextorchestrator.service.IngestionService;
import com.adlanda.contextorchestrator.service.IngestionService.IngestionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Runs document ingestion on application startup.
 *
 * Reads all documents from the configured docs directory,
 * and stores them in the vector store. Spring AI handles embedding
 * generation internally when documents are added.
 *
 * Iteration 2: Uses PGVector for persistent storage and supports
 * incremental ingestion (only new/changed files are processed).
 * Also cleans up orphaned chunks from deleted/modified files.
 */
@Component
@Order(1) // Run before StartupInfoLogger
public class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final IngestionService ingestionService;
    private final PgVectorStore vectorStore;
    private final IngestionHealthIndicator healthIndicator;
    private final IngestionProperties properties;

    public IngestionRunner(IngestionService ingestionService,
                          PgVectorStore vectorStore,
                          IngestionHealthIndicator healthIndicator,
                          IngestionProperties properties) {
        this.ingestionService = ingestionService;
        this.vectorStore = vectorStore;
        this.healthIndicator = healthIndicator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("Document ingestion is disabled");
            return;
        }

        log.info("Starting document ingestion...");

        try {
            // 1. Read and chunk documents (with incremental ingestion support)
            // Also cleans up deleted files and orphaned chunks
            IngestionSummary summary = ingestionService.ingestAllDocumentsWithSummary();

            if (!summary.hasChanges()) {
                log.info("No changes detected. Index is up to date ({} files tracked)", 
                        summary.skippedFiles());
                healthIndicator.markHealthy(summary);
                return;
            }

            // 2. Store new chunks in PGVector store
            // Spring AI handles embedding generation internally
            if (!summary.chunks().isEmpty()) {
                vectorStore.storeAll(summary.chunks());
            }

            log.info("Ingestion complete: {} files processed, {} skipped, {} deleted, {} chunks indexed",
                    summary.processedFiles(),
                    summary.skippedFiles(),
                    summary.deletedFiles(),
                    summary.totalChunks());

            healthIndicator.markHealthy(summary);

        } catch (DataAccessException e) {
            String error = "Database error during ingestion: " + e.getMessage();
            log.error(error, e);
            healthIndicator.markUnhealthy(error);
            throw new IllegalStateException(error, e);
        } catch (Exception e) {
            String error = "Failed to ingest documents: " + e.getMessage();
            log.error(error, e);
            healthIndicator.markUnhealthy(error);
            throw new IllegalStateException(error, e);
        }
    }
}
