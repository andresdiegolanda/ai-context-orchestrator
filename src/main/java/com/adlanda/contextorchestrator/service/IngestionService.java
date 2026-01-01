package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.config.IngestionProperties;
import com.adlanda.contextorchestrator.entity.IngestedSource;
import com.adlanda.contextorchestrator.model.DocumentChunk;
import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import com.adlanda.contextorchestrator.repository.PgVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Service responsible for reading and chunking documents from the filesystem.
 *
 * Iteration 2 adds:
 * - Incremental ingestion: Only process files that have changed (based on SHA-256 hash)
 * - File hash tracking: Store hashes in database to detect changes across restarts
 * - Orphan cleanup: Delete old chunks when files are modified or deleted
 * - Deleted file detection: Remove chunks for files that no longer exist
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final FileHashService fileHashService;
    private final IngestedSourceRepository ingestedSourceRepository;
    private final IngestionProperties ingestionProperties;
    private final PgVectorStore pgVectorStore;

    @Value("${orchestrator.docs.path:./docs}")
    private String docsPath;

    @Value("${orchestrator.chunking.max-tokens:512}")
    private int maxTokens;

    public IngestionService(FileHashService fileHashService,
                            IngestedSourceRepository ingestedSourceRepository,
                            IngestionProperties ingestionProperties,
                            PgVectorStore pgVectorStore) {
        this.fileHashService = fileHashService;
        this.ingestedSourceRepository = ingestedSourceRepository;
        this.ingestionProperties = ingestionProperties;
        this.pgVectorStore = pgVectorStore;
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
     * Summary of the ingestion process.
     */
    public record IngestionSummary(
            int processedFiles,
            int skippedFiles,
            int deletedFiles,
            List<DocumentChunk> chunks
    ) {
        public int totalChunks() {
            return chunks.size();
        }

        public boolean hasChanges() {
            return processedFiles > 0 || deletedFiles > 0;
        }
    }

    /**
     * Scans the configured docs directory and returns chunks from all markdown files.
     * In incremental mode, only processes files that have changed since last ingestion.
     * Also cleans up orphaned chunks from deleted files.
     */
    @Transactional
    public IngestionSummary ingestAllDocumentsWithSummary() {
        Path docsDir = Path.of(docsPath);

        if (!Files.exists(docsDir)) {
            log.warn("Docs directory does not exist: {}", docsPath);
            return new IngestionSummary(0, 0, 0, List.of());
        }

        // Phase 1: Clean up deleted files
        int deletedFiles = cleanupDeletedFiles(docsDir);

        // Phase 2: Process current files
        List<DocumentChunk> allChunks = new ArrayList<>();
        int skippedCount = 0;
        int processedCount = 0;

        try (Stream<Path> paths = Files.walk(docsDir)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedFile)
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

        log.info("Ingestion complete: {} files processed, {} skipped (unchanged), {} deleted, {} total chunks",
                processedCount, skippedCount, deletedFiles, allChunks.size());
        
        return new IngestionSummary(processedCount, skippedCount, deletedFiles, allChunks);
    }

    /**
     * Scans the configured docs directory and returns chunks from all markdown files.
     * Backward compatible method that returns just the chunks list.
     */
    @Transactional
    public List<DocumentChunk> ingestAllDocuments() {
        return ingestAllDocumentsWithSummary().chunks();
    }

    /**
     * Cleans up chunks and records for files that have been deleted from disk.
     *
     * @param docsDir The docs directory to scan
     * @return Number of deleted files cleaned up
     */
    private int cleanupDeletedFiles(Path docsDir) {
        // Get current files on disk
        Set<String> currentFiles = new HashSet<>();
        try (Stream<Path> paths = Files.walk(docsDir)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isSupportedFile)
                 .forEach(p -> currentFiles.add(
                     docsDir.relativize(p).toString().replace("\\", "/")
                 ));
        } catch (IOException e) {
            log.warn("Error scanning documents directory for cleanup: {}", e.getMessage());
            return 0;
        }

        // Find and remove orphaned records
        int deletedCount = 0;
        List<IngestedSource> allSources = ingestedSourceRepository.findAll();

        for (IngestedSource source : allSources) {
            String normalizedPath = source.getFilePath().replace("\\", "/");
            if (!currentFiles.contains(normalizedPath)) {
                log.info("File '{}' no longer exists, removing {} chunks from index",
                        source.getFilePath(), source.getChunkCount());
                pgVectorStore.deleteBySourceFile(source.getFilePath());
                ingestedSourceRepository.delete(source);
                deletedCount++;
            }
        }

        if (deletedCount > 0) {
            log.info("Cleaned up {} deleted files from index", deletedCount);
        }

        return deletedCount;
    }

    /**
     * Checks if a file is supported for ingestion.
     */
    private boolean isSupportedFile(Path path) {
        String name = path.toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".adoc");
    }

    /**
     * Processes a single file, checking if it needs to be re-ingested.
     * Deletes old chunks before re-ingesting changed files.
     *
     * @param filePath Path to the file
     * @return Result containing chunks (if processed) or skip reason
     */
    @Transactional
    public FileIngestionResult processFile(Path filePath) throws IOException {
        String relativePath = Path.of(docsPath).relativize(filePath).toString().replace("\\", "/");
        String currentHash = fileHashService.computeHash(filePath);
        long fileSize = fileHashService.getFileSize(filePath);

        // Check if incremental ingestion is enabled
        if (ingestionProperties.isIncremental()) {
            // Check if this exact file (path + hash) already exists
            if (ingestedSourceRepository.existsByFilePathAndFileHash(relativePath, currentHash)) {
                return FileIngestionResult.skipped(relativePath, "unchanged");
            }

            // Check if file exists but has changed - delete old chunks first
            Optional<IngestedSource> existingSource = ingestedSourceRepository.findByFilePath(relativePath);
            if (existingSource.isPresent()) {
                IngestedSource source = existingSource.get();
                log.info("File {} has changed, deleting {} old chunks before re-ingesting...", 
                        relativePath, source.getChunkCount());
                pgVectorStore.deleteBySourceFile(relativePath);
                ingestedSourceRepository.delete(source);
            }
        }

        // Read and chunk the file
        List<DocumentChunk> chunks = ingestFile(filePath, currentHash);

        // Create new ingested source record
        IngestedSource source = new IngestedSource(relativePath, currentHash, fileSize, chunks.size());
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
        String relativePath = Path.of(docsPath).relativize(filePath).toString().replace("\\", "/");

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