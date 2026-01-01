package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.config.IngestionProperties;
import com.adlanda.contextorchestrator.entity.IngestedSource;
import com.adlanda.contextorchestrator.model.DocumentChunk;
import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Service responsible for reading and chunking documents from the filesystem.
 *
 * Iteration 2 adds:
 * - Incremental ingestion: Only process files that have changed (based on SHA-256 hash)
 * - File hash tracking: Store hashes in database to detect changes across restarts
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final FileHashService fileHashService;
    private final IngestedSourceRepository ingestedSourceRepository;
    private final IngestionProperties ingestionProperties;

    @Value("${orchestrator.docs.path:./docs}")
    private String docsPath;

    @Value("${orchestrator.chunking.max-tokens:512}")
    private int maxTokens;

    public IngestionService(FileHashService fileHashService,
                            IngestedSourceRepository ingestedSourceRepository,
                            IngestionProperties ingestionProperties) {
        this.fileHashService = fileHashService;
        this.ingestedSourceRepository = ingestedSourceRepository;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * Result of processing a single file.
     */
    public record FileIngestionResult(
            String filePath,
            List<DocumentChunk> chunks,
            boolean wasSkipped,
            String reason
    ) {
        public static FileIngestionResult skipped(String filePath, String reason) {
            return new FileIngestionResult(filePath, List.of(), true, reason);
        }

        public static FileIngestionResult ingested(String filePath, List<DocumentChunk> chunks) {
            return new FileIngestionResult(filePath, chunks, false, null);
        }
    }

    /**
     * Scans the configured docs directory and returns chunks from all markdown files.
     * In incremental mode, only processes files that have changed since last ingestion.
     */
    @Transactional
    public List<DocumentChunk> ingestAllDocuments() {
        Path docsDir = Path.of(docsPath);

        if (!Files.exists(docsDir)) {
            log.warn("Docs directory does not exist: {}", docsPath);
            return List.of();
        }

        List<DocumentChunk> allChunks = new ArrayList<>();
        int skippedCount = 0;
        int processedCount = 0;

        try (Stream<Path> paths = Files.walk(docsDir)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .toList();

            for (Path path : files) {
                try {
                    FileIngestionResult result = processFile(path);
                    if (result.wasSkipped()) {
                        skippedCount++;
                        log.debug("Skipped {} ({})", path.getFileName(), result.reason());
                    } else {
                        allChunks.addAll(result.chunks());
                        processedCount++;
                        log.info("Ingested {} chunks from {}", result.chunks().size(), path.getFileName());
                    }
                } catch (IOException e) {
                    log.error("Failed to ingest file: {}", path, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to walk docs directory: {}", docsPath, e);
        }

        log.info("Ingestion complete: {} files processed, {} skipped (unchanged), {} total chunks",
                processedCount, skippedCount, allChunks.size());
        return allChunks;
    }

    /**
     * Processes a single file, checking if it needs to be re-ingested.
     *
     * @param filePath Path to the file
     * @return Result containing chunks (if processed) or skip reason
     */
    @Transactional
    public FileIngestionResult processFile(Path filePath) throws IOException {
        String relativePath = Path.of(docsPath).relativize(filePath).toString();
        String currentHash = fileHashService.computeHash(filePath);
        long fileSize = fileHashService.getFileSize(filePath);

        // Check if incremental ingestion is enabled
        if (ingestionProperties.isIncremental()) {
            // Check if this exact file (path + hash) already exists
            if (ingestedSourceRepository.existsByFilePathAndFileHash(relativePath, currentHash)) {
                return FileIngestionResult.skipped(relativePath, "unchanged");
            }

            // Check if file exists but has changed
            Optional<IngestedSource> existingSource = ingestedSourceRepository.findByFilePath(relativePath);
            if (existingSource.isPresent()) {
                log.info("File {} has changed, re-ingesting...", relativePath);
                // Will be handled by the caller - old chunks need to be deleted
            }
        }

        // Read and chunk the file
        List<DocumentChunk> chunks = ingestFile(filePath, currentHash);

        // Update or create the ingested source record
        IngestedSource source = ingestedSourceRepository.findByFilePath(relativePath)
                .orElse(new IngestedSource(relativePath, currentHash, fileSize, chunks.size()));

        source.setFileHash(currentHash);
        source.setFileSize(fileSize);
        source.setChunkCount(chunks.size());
        ingestedSourceRepository.save(source);

        return FileIngestionResult.ingested(relativePath, chunks);
    }

    /**
     * Reads a single file and splits it into chunks.
     *
     * @param filePath Path to the file
     * @param fileHash SHA-256 hash of the file content
     * @return List of document chunks
     */
    public List<DocumentChunk> ingestFile(Path filePath, String fileHash) throws IOException {
        String content = Files.readString(filePath);
        String relativePath = Path.of(docsPath).relativize(filePath).toString();

        return chunkContent(content, relativePath, fileHash);
    }

    /**
     * Reads a single file and splits it into chunks.
     * Computes hash automatically if not provided.
     */
    public List<DocumentChunk> ingestFile(Path filePath) throws IOException {
        String fileHash = fileHashService.computeHash(filePath);
        return ingestFile(filePath, fileHash);
    }

    /**
     * Splits content into chunks using naive paragraph-based splitting.
     *
     * Iteration 1: Simple splitting by double newlines (paragraphs).
     * Later iterations will implement:
     * - Markdown-aware splitting (respect headers, code blocks)
     * - Token counting instead of character counting
     * - Overlap between chunks
     *
     * @param content   The text content to chunk
     * @param sourceFile The source file path for metadata
     * @param fileHash  The SHA-256 hash of the source file
     */
    List<DocumentChunk> chunkContent(String content, String sourceFile, String fileHash) {
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
                        chunkIndex++,
                        fileHash
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
                    chunkIndex,
                    fileHash
            ));
        }

        return chunks;
    }

    /**
     * Backward compatible method without hash parameter.
     */
    List<DocumentChunk> chunkContent(String content, String sourceFile) {
        String hash = fileHashService.computeHash(content);
        return chunkContent(content, sourceFile, hash);
    }

    /**
     * Gets the docs path.
     */
    public String getDocsPath() {
        return docsPath;
    }
}