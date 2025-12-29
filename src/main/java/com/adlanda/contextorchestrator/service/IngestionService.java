package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Service responsible for reading and chunking documents from the filesystem.
 *
 * In Iteration 1, we use naive paragraph-based chunking.
 * Later iterations will implement smarter markdown-aware chunking.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    @Value("${orchestrator.docs.path:./docs}")
    private String docsPath;

    @Value("${orchestrator.chunking.max-tokens:512}")
    private int maxTokens;

    /**
     * Scans the configured docs directory and returns chunks from all markdown files.
     */
    public List<DocumentChunk> ingestAllDocuments() {
        Path docsDir = Path.of(docsPath);

        if (!Files.exists(docsDir)) {
            log.warn("Docs directory does not exist: {}", docsPath);
            return List.of();
        }

        List<DocumentChunk> allChunks = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(docsDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                 .forEach(path -> {
                     try {
                         List<DocumentChunk> chunks = ingestFile(path);
                         allChunks.addAll(chunks);
                         log.info("Ingested {} chunks from {}", chunks.size(), path.getFileName());
                     } catch (IOException e) {
                         log.error("Failed to ingest file: {}", path, e);
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to walk docs directory: {}", docsPath, e);
        }

        log.info("Total chunks ingested: {}", allChunks.size());
        return allChunks;
    }

    /**
     * Reads a single file and splits it into chunks.
     */
    public List<DocumentChunk> ingestFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = Path.of(docsPath).relativize(filePath).toString();

        return chunkContent(content, relativePath);
    }

    /**
     * Splits content into chunks using naive paragraph-based splitting.
     *
     * Iteration 1: Simple splitting by double newlines (paragraphs).
     * Later iterations will implement:
     * - Markdown-aware splitting (respect headers, code blocks)
     * - Token counting instead of character counting
     * - Overlap between chunks
     */
    List<DocumentChunk> chunkContent(String content, String sourceFile) {
        List<DocumentChunk> chunks = new ArrayList<>();

        // Split by double newlines (paragraphs)
        String[] paragraphs = content.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Rough token estimate: ~4 characters per token
            int estimatedTokens = (currentChunk.length() + trimmed.length()) / 4;

            if (estimatedTokens > maxTokens && currentChunk.length() > 0) {
                // Save current chunk and start new one
                chunks.add(DocumentChunk.withoutEmbedding(
                        UUID.randomUUID().toString(),
                        currentChunk.toString().trim(),
                        sourceFile,
                        chunkIndex++
                ));
                currentChunk = new StringBuilder();
            }

            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(trimmed);
        }

        // Don't forget the last chunk
        if (currentChunk.length() > 0) {
            chunks.add(DocumentChunk.withoutEmbedding(
                    UUID.randomUUID().toString(),
                    currentChunk.toString().trim(),
                    sourceFile,
                    chunkIndex
            ));
        }

        return chunks;
    }
}
