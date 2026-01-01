# Iteration 2: Issues Response and Implementation Plan

This document addresses each issue identified in the Iteration 2 Issues analysis, providing detailed answers, implementation decisions, and code solutions.

---

## Issue #1: Orphaned Chunks on File Change (CRITICAL)

### Assessment

**Acknowledged as CRITICAL.** This is indeed a significant bug that undermines the entire purpose of incremental ingestion. Without proper cleanup, the vector store accumulates stale data, leading to:
- Polluted search results mixing old and new content
- Unbounded storage growth
- Increased query latency as the index grows

### Implementation Decision

Implementing the recommended fix using `JdbcTemplate` for native SQL deletion since Spring AI's `VectorStore` interface lacks metadata-based deletion support.

### Solution Code

**1. Update PgVectorStore.java:**

```java
@Repository
public class PgVectorStore {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    
    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    public PgVectorStore(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deletes all chunks associated with a source file from the vector store.
     * Uses native SQL since Spring AI VectorStore doesn't support metadata-based deletion.
     * 
     * @param sourceFile the relative path of the source file
     * @return number of chunks deleted
     */
    public int deleteBySourceFile(String sourceFile) {
        String sql = "DELETE FROM vector_store WHERE metadata->>'sourceFile' = ?";
        int deleted = jdbcTemplate.update(sql, sourceFile);
        log.info("Deleted {} chunks for source file: {}", deleted, sourceFile);
        return deleted;
    }

    public void storeAll(List<DocumentChunk> chunks) {
        List<Document> documents = chunks.stream()
                .map(this::toDocument)
                .toList();
        vectorStore.add(documents);
        log.info("Stored {} chunks in vector store", chunks.size());
    }

    // ... rest of existing methods
}
```

**2. Update IngestionService.java:**

```java
@Service
public class IngestionService {

    private final PgVectorStore pgVectorStore;
    // ... other dependencies

    public IngestionService(
            PgVectorStore pgVectorStore,
            FileHashService fileHashService,
            IngestedSourceRepository ingestedSourceRepository,
            IngestionProperties ingestionProperties) {
        this.pgVectorStore = pgVectorStore;
        // ... initialize others
    }

    @Transactional
    public FileIngestionResult processFile(Path filePath) throws IOException {
        String relativePath = Path.of(docsPath).relativize(filePath).toString();
        String currentHash = fileHashService.computeHash(filePath);
        
        if (ingestionProperties.isIncremental()) {
            // Check if file is unchanged
            if (ingestedSourceRepository.existsByFilePathAndFileHash(relativePath, currentHash)) {
                return FileIngestionResult.skipped(relativePath, "unchanged");
            }
            
            // Check if file was previously ingested (but has changed)
            Optional<IngestedSource> existingSource = ingestedSourceRepository.findByFilePath(relativePath);
            if (existingSource.isPresent()) {
                log.info("File {} has changed, deleting {} old chunks...", 
                    relativePath, existingSource.get().getChunkCount());
                pgVectorStore.deleteBySourceFile(relativePath);
                ingestedSourceRepository.delete(existingSource.get());
            }
        }
        
        // Continue with ingestion...
    }
}
```

### Verification Steps

1. Ingest a document, note the chunk count
2. Modify the document content
3. Re-run ingestion
4. Query `SELECT COUNT(*) FROM vector_store WHERE metadata->>'sourceFile' = 'path/to/file.md'`
5. Should match new chunk count exactly (no orphans)

---

## Issue #2: Dead Code Creates Confusion

### Assessment

Valid concern. Having two vector store implementations with unclear activation rules is a maintenance burden.

### Implementation Decision

**Option A: Delete InMemoryVectorStore** - Selected.

Rationale:
- The project is committed to PGVector for persistence
- In-memory store was only useful during initial development
- Keeping dead code creates cognitive overhead
- If needed later, it can be restored from git history

### Action

Delete `src/main/java/com/adlanda/contextorchestrator/vectorstore/InMemoryVectorStore.java`

If flexibility is needed in the future, we can reintroduce it with proper `@Profile` or `@ConditionalOnProperty` annotations as documented in the issues analysis.

---

## Issue #3: Unused Database Table

### Assessment

Correct. The `document_chunks` table in `init.sql` is never used since Spring AI manages its own `vector_store` table.

### Implementation Decision

**Option A: Remove unused table definition** - Selected.

### Updated init.sql

```sql
-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Track ingested source files for incremental ingestion
CREATE TABLE IF NOT EXISTS ingested_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_path VARCHAR(500) UNIQUE NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    file_size BIGINT,
    chunk_count INTEGER DEFAULT 0,
    ingested_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingested_sources_file_path ON ingested_sources (file_path);
CREATE INDEX IF NOT EXISTS idx_ingested_sources_hash ON ingested_sources (file_hash);

-- Note: vector_store table is auto-created by Spring AI PgVectorStore
-- with initialize-schema=true configuration
```

---

## Issue #4: Missing File Deletion Detection

### Assessment

Valid issue. Without deletion detection, removing a source file leaves orphaned data in both `vector_store` and `ingested_sources`.

### Implementation Decision

Implement cleanup detection as a pre-step in `ingestAllDocuments()`.

### Solution Code

```java
@Transactional
public IngestionSummary ingestAllDocuments() {
    Path docsDir = Path.of(docsPath);
    
    // Phase 1: Detect and clean up deleted files
    int deletedFiles = cleanupDeletedFiles(docsDir);
    
    // Phase 2: Process current files
    List<DocumentChunk> allChunks = new ArrayList<>();
    int processedCount = 0;
    int skippedCount = 0;
    
    try (Stream<Path> paths = Files.walk(docsDir)) {
        List<Path> files = paths
            .filter(Files::isRegularFile)
            .filter(p -> isSupportedFile(p))
            .toList();
            
        for (Path file : files) {
            FileIngestionResult result = processFile(file);
            if (result.wasSkipped()) {
                skippedCount++;
            } else {
                allChunks.addAll(result.chunks());
                processedCount++;
            }
        }
    }
    
    return new IngestionSummary(processedCount, skippedCount, deletedFiles, allChunks);
}

private int cleanupDeletedFiles(Path docsDir) {
    // Get current files on disk
    Set<String> currentFiles = new HashSet<>();
    try (Stream<Path> paths = Files.walk(docsDir)) {
        paths.filter(Files::isRegularFile)
             .filter(this::isSupportedFile)
             .forEach(p -> currentFiles.add(docsDir.relativize(p).toString().replace("\\", "/")));
    } catch (IOException e) {
        log.warn("Error scanning documents directory: {}", e.getMessage());
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

private boolean isSupportedFile(Path path) {
    String name = path.toString().toLowerCase();
    return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".adoc");
}
```

### New IngestionSummary Record

```java
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
```

---

## Issue #5: Potential Double Embedding

### Assessment

This requires verification of Spring AI's behavior. Looking at Spring AI's `PgVectorStore` implementation:

- When `add(List<Document>)` is called, it checks if documents have embeddings
- If embeddings are missing, it calls the configured `EmbeddingModel` to generate them
- If embeddings exist, it uses them directly

### Implementation Decision

**Streamlined approach:** Let Spring AI handle embedding entirely.

Remove explicit embedding step in `IngestionRunner` since Spring AI will handle it during `vectorStore.add()`. This:
- Reduces code complexity
- Avoids potential double-embedding cost
- Centralizes embedding logic in one place

### Simplified IngestionRunner

```java
@Component
public class IngestionRunner implements ApplicationRunner {

    private final IngestionService ingestionService;
    private final PgVectorStore vectorStore;
    
    // Note: EmbeddingService no longer needed here
    
    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting document ingestion...");
        
        IngestionSummary summary = ingestionService.ingestAllDocuments();
        
        if (!summary.hasChanges()) {
            log.info("No changes detected. Index is up to date.");
            return;
        }
        
        if (!summary.chunks().isEmpty()) {
            // Spring AI's VectorStore.add() handles embedding internally
            vectorStore.storeAll(summary.chunks());
        }
        
        log.info("Ingestion complete: {} files processed, {} skipped, {} deleted, {} chunks indexed",
            summary.processedFiles(), 
            summary.skippedFiles(), 
            summary.deletedFiles(),
            summary.totalChunks());
    }
}
```

### Alternative: Preserve Pre-computed Embeddings

If pre-computing embeddings offers benefits (batch optimization, caching), update `toDocument()`:

```java
private Document toDocument(DocumentChunk chunk) {
    Map<String, Object> metadata = Map.of(
        "sourceFile", chunk.sourceFile(),
        "chunkIndex", chunk.chunkIndex(),
        "totalChunks", chunk.totalChunks()
    );
    
    Document doc = new Document(chunk.id(), chunk.content(), metadata);
    
    // Pass through pre-computed embedding if available
    if (chunk.embedding() != null && chunk.embedding().length > 0) {
        doc.setEmbedding(chunk.embedding());
    }
    
    return doc;
}
```

---

## Issue #6: No Tests for New Functionality

### Assessment

**High priority.** Tests are essential for:
- Proving the code works correctly
- Preventing regressions during refactoring
- Documenting expected behavior
- Demonstrating engineering discipline

### Implementation Plan

Creating a comprehensive test suite for all Iteration 2 components.

### Test Files to Create

**1. FileHashServiceTest.java**

```java
package com.adlanda.contextorchestrator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileHashServiceTest {

    private FileHashService hashService;

    @BeforeEach
    void setUp() {
        hashService = new FileHashService();
    }

    @Test
    void computeHash_sameContent_returnsSameHash() {
        String hash1 = hashService.computeHash("hello world");
        String hash2 = hashService.computeHash("hello world");
        
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeHash_differentContent_returnsDifferentHash() {
        String hash1 = hashService.computeHash("hello world");
        String hash2 = hashService.computeHash("hello universe");
        
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeHash_returnsValidSha256Format() {
        String hash = hashService.computeHash("test content");
        
        assertThat(hash)
            .hasSize(64)  // SHA-256 produces 64 hex characters
            .matches("[a-f0-9]+");
    }

    @Test
    void computeHash_emptyString_returnsConsistentHash() {
        String hash = hashService.computeHash("");
        
        // SHA-256 of empty string is well-known
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void computeHash_file_matchesContentHash(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        String content = "file content for hashing";
        Files.writeString(file, content);
        
        String fileHash = hashService.computeHash(file);
        String contentHash = hashService.computeHash(content);
        
        assertThat(fileHash).isEqualTo(contentHash);
    }

    @Test
    void computeHash_whitespaceMatters() {
        String hash1 = hashService.computeHash("hello world");
        String hash2 = hashService.computeHash("hello  world");  // extra space
        
        assertThat(hash1).isNotEqualTo(hash2);
    }
}
```

**2. PgVectorStoreTest.java**

```java
package com.adlanda.contextorchestrator.vectorstore;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgVectorStoreTest {

    @Mock
    private VectorStore vectorStore;
    
    @Mock
    private JdbcTemplate jdbcTemplate;
    
    private PgVectorStore pgVectorStore;

    @BeforeEach
    void setUp() {
        pgVectorStore = new PgVectorStore(vectorStore, jdbcTemplate);
    }

    @Test
    void deleteBySourceFile_executesCorrectSql() {
        String sourceFile = "docs/test-file.md";
        when(jdbcTemplate.update(anyString(), eq(sourceFile))).thenReturn(5);
        
        int deleted = pgVectorStore.deleteBySourceFile(sourceFile);
        
        assertThat(deleted).isEqualTo(5);
        verify(jdbcTemplate).update(
            "DELETE FROM vector_store WHERE metadata->>'sourceFile' = ?",
            sourceFile
        );
    }

    @Test
    void deleteBySourceFile_returnsZeroWhenNoMatches() {
        when(jdbcTemplate.update(anyString(), anyString())).thenReturn(0);
        
        int deleted = pgVectorStore.deleteBySourceFile("nonexistent.md");
        
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeAll_convertsChunksToDocuments() {
        DocumentChunk chunk = new DocumentChunk(
            UUID.randomUUID().toString(),
            "Test content",
            "docs/test.md",
            0,
            1,
            new float[0]
        );
        
        pgVectorStore.storeAll(List.of(chunk));
        
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        
        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getContent()).isEqualTo("Test content");
        assertThat(documents.get(0).getMetadata()).containsEntry("sourceFile", "docs/test.md");
    }
}
```

**3. IngestionServiceIncrementalTest.java**

```java
package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.config.IngestionProperties;
import com.adlanda.contextorchestrator.model.IngestedSource;
import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import com.adlanda.contextorchestrator.vectorstore.PgVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceIncrementalTest {

    @Mock private FileHashService fileHashService;
    @Mock private IngestedSourceRepository repository;
    @Mock private IngestionProperties properties;
    @Mock private PgVectorStore pgVectorStore;
    @Mock private ChunkingService chunkingService;
    
    @TempDir
    Path tempDir;
    
    private IngestionService service;

    @BeforeEach
    void setUp() {
        when(properties.getDocsPath()).thenReturn(tempDir.toString());
        service = new IngestionService(
            pgVectorStore, fileHashService, repository, 
            properties, chunkingService
        );
    }

    @Test
    void processFile_unchanged_skipsIngestion() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Test Content");
        
        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash123");
        when(repository.existsByFilePathAndFileHash("test.md", "hash123")).thenReturn(true);
        
        // Execute
        FileIngestionResult result = service.processFile(testFile);
        
        // Verify
        assertThat(result.wasSkipped()).isTrue();
        assertThat(result.reason()).isEqualTo("unchanged");
        verify(pgVectorStore, never()).deleteBySourceFile(anyString());
    }

    @Test
    void processFile_changed_deletesOldChunksAndReingests() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Updated Content");
        
        IngestedSource existingSource = new IngestedSource();
        existingSource.setFilePath("test.md");
        existingSource.setChunkCount(3);
        
        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("newHash");
        when(repository.existsByFilePathAndFileHash(anyString(), anyString())).thenReturn(false);
        when(repository.findByFilePath("test.md")).thenReturn(Optional.of(existingSource));
        
        // Execute
        FileIngestionResult result = service.processFile(testFile);
        
        // Verify old chunks were deleted
        verify(pgVectorStore).deleteBySourceFile("test.md");
        verify(repository).delete(existingSource);
        assertThat(result.wasSkipped()).isFalse();
    }

    @Test
    void processFile_newFile_ingestsWithoutDeletion() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("new-file.md");
        Files.writeString(testFile, "# New Document");
        
        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash456");
        when(repository.existsByFilePathAndFileHash(anyString(), anyString())).thenReturn(false);
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        
        // Execute
        FileIngestionResult result = service.processFile(testFile);
        
        // Verify no deletion attempted for new file
        verify(pgVectorStore, never()).deleteBySourceFile(anyString());
        assertThat(result.wasSkipped()).isFalse();
    }

    @Test
    void processFile_nonIncrementalMode_alwaysProcesses() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Content");
        
        when(properties.isIncremental()).thenReturn(false);
        
        // Execute
        FileIngestionResult result = service.processFile(testFile);
        
        // Verify - should process without checking hash
        verify(fileHashService, never()).computeHash(any(Path.class));
        assertThat(result.wasSkipped()).isFalse();
    }
}
```

**4. Integration Test with Testcontainers**

```java
package com.adlanda.contextorchestrator;

import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import com.adlanda.contextorchestrator.service.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class IngestionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("db/init.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> "true");
    }

    @Autowired
    private IngestionService ingestionService;
    
    @Autowired
    private IngestedSourceRepository repository;

    @Test
    void fullIngestionFlow_persistsSourceRecords() {
        // First ingestion
        var summary = ingestionService.ingestAllDocuments();
        long initialCount = repository.count();
        
        assertThat(initialCount).isGreaterThan(0);
        assertThat(summary.processedFiles()).isEqualTo((int) initialCount);
        
        // Second run - should skip all unchanged files
        var secondSummary = ingestionService.ingestAllDocuments();
        
        assertThat(repository.count()).isEqualTo(initialCount);
        assertThat(secondSummary.skippedFiles()).isEqualTo((int) initialCount);
        assertThat(secondSummary.processedFiles()).isEqualTo(0);
    }
}
```

---

## Issue #7: Configuration Contradiction

### Assessment

The observation is accurate but the current configuration works correctly because:
- Hibernate manages JPA entities (`IngestedSource` → `ingested_sources` table)
- Spring AI manages the `vector_store` table independently

However, clarity is important for maintainability.

### Implementation Decision

Add explicit comments and use consistent strategy:

### Updated application.properties

```properties
# ===========================================
# Database Configuration
# ===========================================
spring.datasource.url=jdbc:postgresql://localhost:5432/contextorchestrator
spring.datasource.username=postgres
spring.datasource.password=postgres

# ===========================================
# Schema Management Strategy
# ===========================================
# JPA/Hibernate: manages ingested_sources table
# - validate: assumes schema exists (created by init.sql or Flyway in production)
# - For development, change to 'update' to auto-create
spring.jpa.hibernate.ddl-auto=validate

# Spring AI PGVector: manages vector_store table
# - Auto-creates the vector_store table if not exists
# - In production with Flyway, set to false
spring.ai.vectorstore.pgvector.initialize-schema=true

# Note: Both tables are initially created by db/init.sql (or Flyway migrations)
# This configuration validates JPA entities and allows Spring AI to ensure
# its internal table structure is correct.

# ===========================================
# Vector Store Configuration  
# ===========================================
spring.ai.vectorstore.pgvector.dimensions=1536
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.index-type=HNSW
```

### Production Configuration (application-prod.properties)

```properties
# Production: All schema changes via Flyway migrations
spring.jpa.hibernate.ddl-auto=validate
spring.ai.vectorstore.pgvector.initialize-schema=false
spring.flyway.enabled=true
```

---

## Issue #8: Silent Failure on Startup

### Assessment

Valid concern. Silent failures mask problems and lead to poor user experience.

### Implementation Decision

**Hybrid approach:**
1. Use fail-fast for critical ingestion errors (can't connect to DB, can't read docs directory)
2. Use health indicator for monitoring ongoing ingestion status
3. Log warnings for individual file failures but continue processing

### Solution

**1. Update IngestionRunner with selective error handling:**

```java
@Component
@Order(10)  // Run after database initialization
public class IngestionRunner implements ApplicationRunner {

    private final IngestionService ingestionService;
    private final PgVectorStore vectorStore;
    private final IngestionHealthIndicator healthIndicator;
    
    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting document ingestion...");
        
        try {
            IngestionSummary summary = ingestionService.ingestAllDocuments();
            
            if (!summary.hasChanges()) {
                log.info("No changes detected. Index is up to date ({} files tracked)", 
                    summary.skippedFiles());
                healthIndicator.markHealthy(summary);
                return;
            }
            
            if (!summary.chunks().isEmpty()) {
                vectorStore.storeAll(summary.chunks());
            }
            
            log.info("Ingestion complete: {} processed, {} skipped, {} deleted, {} chunks",
                summary.processedFiles(), 
                summary.skippedFiles(), 
                summary.deletedFiles(),
                summary.totalChunks());
                
            healthIndicator.markHealthy(summary);
            
        } catch (IOException e) {
            // Critical error - can't access documents directory
            String error = "Cannot access documents directory: " + e.getMessage();
            log.error(error, e);
            healthIndicator.markUnhealthy(error);
            throw new IllegalStateException(error, e);  // Fail startup
            
        } catch (DataAccessException e) {
            // Critical error - database issues
            String error = "Database error during ingestion: " + e.getMessage();
            log.error(error, e);
            healthIndicator.markUnhealthy(error);
            throw new IllegalStateException(error, e);  // Fail startup
        }
    }
}
```

**2. Create IngestionHealthIndicator:**

```java
package com.adlanda.contextorchestrator.health;

import com.adlanda.contextorchestrator.service.IngestionSummary;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class IngestionHealthIndicator implements HealthIndicator {
    
    private final AtomicReference<HealthState> state = new AtomicReference<>(
        new HealthState(false, null, "Not yet run", null)
    );

    public void markHealthy(IngestionSummary summary) {
        state.set(new HealthState(
            true,
            summary,
            null,
            Instant.now()
        ));
    }
    
    public void markUnhealthy(String error) {
        state.set(new HealthState(
            false,
            null,
            error,
            Instant.now()
        ));
    }

    @Override
    public Health health() {
        HealthState current = state.get();
        
        if (current.healthy()) {
            return Health.up()
                .withDetail("lastRun", current.timestamp())
                .withDetail("filesTracked", current.summary() != null ? 
                    current.summary().skippedFiles() + current.summary().processedFiles() : 0)
                .withDetail("lastChunksIndexed", current.summary() != null ?
                    current.summary().totalChunks() : 0)
                .build();
        }
        
        return Health.down()
            .withDetail("error", current.error())
            .withDetail("lastAttempt", current.timestamp())
            .build();
    }
    
    private record HealthState(
        boolean healthy,
        IngestionSummary summary,
        String error,
        Instant timestamp
    ) {}
}
```

**3. Health endpoint response examples:**

Healthy:
```json
{
  "status": "UP",
  "components": {
    "ingestion": {
      "status": "UP",
      "details": {
        "lastRun": "2026-01-01T10:30:00Z",
        "filesTracked": 15,
        "lastChunksIndexed": 47
      }
    }
  }
}
```

Unhealthy:
```json
{
  "status": "DOWN",
  "components": {
    "ingestion": {
      "status": "DOWN",
      "details": {
        "error": "Cannot access documents directory: Permission denied",
        "lastAttempt": "2026-01-01T10:30:00Z"
      }
    }
  }
}
```

---

## Summary of Implementation Actions

| Issue | Action | Status |
|-------|--------|--------|
| #1 Orphaned Chunks | Add `JdbcTemplate`-based deletion to `PgVectorStore`, call before re-ingesting | Ready to implement |
| #2 Dead Code | Delete `InMemoryVectorStore.java` | Ready to implement |
| #3 Unused Table | Remove `document_chunks` from `init.sql` | Ready to implement |
| #4 File Deletion | Add `cleanupDeletedFiles()` to `IngestionService` | Ready to implement |
| #5 Double Embedding | Remove explicit embedding, let Spring AI handle | Ready to implement |
| #6 Missing Tests | Create test classes as documented above | Ready to implement |
| #7 Config Clarity | Add comments to `application.properties` | Ready to implement |
| #8 Silent Failure | Add fail-fast + health indicator | Ready to implement |

## Recommended Implementation Order

1. **Issue #1** - Critical bug fix (orphaned chunks)
2. **Issue #4** - Completes incremental ingestion story
3. **Issue #6** - Tests to verify fixes work
4. **Issue #5** - Performance optimization
5. **Issue #8** - Production readiness
6. **Issue #2** - Code cleanup
7. **Issue #3** - Schema cleanup
8. **Issue #7** - Documentation clarity

---

## Next Steps

After implementing these fixes:

1. Run the full test suite to verify functionality
2. Perform manual testing of the incremental ingestion workflow
3. Update iteration-2.md documentation to reflect the fixes
4. Create a migration script if deploying to existing environments with orphaned data:

```sql
-- One-time cleanup of orphaned chunks (run manually)
DELETE FROM vector_store v
WHERE NOT EXISTS (
    SELECT 1 FROM ingested_sources s 
    WHERE s.file_path = v.metadata->>'sourceFile'
);
```
