# Iteration 2: Issues and Fixes

A didactic guide to the problems in your current implementation and how to fix them properly.

---

## Issue #1: Orphaned Chunks on File Change (CRITICAL)

### The Problem

When a file is modified, you detect the change and re-ingest it. But you never delete the old chunks from the vector store. This means:

```
docs/spring-ai-guide.md (v1) → chunks A, B, C stored
docs/spring-ai-guide.md (v2) → chunks D, E, F stored
                               chunks A, B, C still exist! ← ORPHANED
```

### Where It Lives

**IngestionService.java lines 137-141:**
```java
Optional<IngestedSource> existingSource = ingestedSourceRepository.findByFilePath(relativePath);
if (existingSource.isPresent()) {
    log.info("File {} has changed, re-ingesting...", relativePath);
    // Will be handled by the caller - old chunks need to be deleted  ← LIE
}
```

**PgVectorStore.java lines 105-109:**
```java
public void deleteBySourceFile(String sourceFile) {
    // Note: Spring AI VectorStore doesn't directly support delete by metadata
    // This would require a custom implementation or direct JDBC access
    log.warn("Delete by source file not yet implemented for PGVector");  ← DOES NOTHING
}
```

### Why It Matters

1. **Stale results**: Queries return outdated content mixed with current content
2. **Growing storage**: Database grows indefinitely with orphan data
3. **Cost waste**: You're paying for storage of garbage data
4. **Defeats the purpose**: Incremental ingestion exists to avoid duplicates

### The Fix

Spring AI's `VectorStore` interface doesn't support deletion by metadata. You need native SQL.

**Step 1: Add JdbcTemplate to PgVectorStore**

```java
@Repository
public class PgVectorStore {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void deleteBySourceFile(String sourceFile) {
        // Spring AI stores metadata as JSONB in the 'metadata' column
        String sql = "DELETE FROM vector_store WHERE metadata->>'sourceFile' = ?";
        int deleted = jdbcTemplate.update(sql, sourceFile);
        log.info("Deleted {} chunks for source file: {}", deleted, sourceFile);
    }
}
```

**Step 2: Call it before re-ingesting in IngestionService**

```java
@Transactional
public FileIngestionResult processFile(Path filePath) throws IOException {
    String relativePath = Path.of(docsPath).relativize(filePath).toString();
    String currentHash = fileHashService.computeHash(filePath);
    
    if (ingestionProperties.isIncremental()) {
        if (ingestedSourceRepository.existsByFilePathAndFileHash(relativePath, currentHash)) {
            return FileIngestionResult.skipped(relativePath, "unchanged");
        }
        
        Optional<IngestedSource> existingSource = ingestedSourceRepository.findByFilePath(relativePath);
        if (existingSource.isPresent()) {
            log.info("File {} has changed, deleting old chunks...", relativePath);
            pgVectorStore.deleteBySourceFile(relativePath);  // ← ADD THIS
        }
    }
    
    // ... rest of method
}
```

**Step 3: Inject PgVectorStore into IngestionService**

Update the constructor to receive `PgVectorStore` as a dependency.

---

## Issue #2: Dead Code Creates Confusion

### The Problem

Two vector store implementations exist:
- `InMemoryVectorStore.java` - from Iteration 1
- `PgVectorStore.java` - from Iteration 2

Both are annotated with `@Repository`, so Spring creates beans for both. You're relying on implicit autowiring to pick the right one. This is:

1. **Confusing**: Someone reading the code doesn't know which is active
2. **Fragile**: Bean resolution order isn't guaranteed
3. **Wasteful**: Dead code is technical debt

### The Fix

**Option A: Delete InMemoryVectorStore entirely (recommended)**

If you're committed to PGVector, just delete the file. Simpler codebase.

**Option B: Use profiles for different environments**

```java
@Repository
@Profile("dev")  // Only active when spring.profiles.active=dev
public class InMemoryVectorStore { ... }

@Repository
@Profile("!dev")  // Active when NOT in dev profile
public class PgVectorStore { ... }
```

**Option C: Use @ConditionalOnProperty**

```java
@Repository
@ConditionalOnProperty(name = "orchestrator.storage.type", havingValue = "memory")
public class InMemoryVectorStore { ... }

@Repository
@ConditionalOnProperty(name = "orchestrator.storage.type", havingValue = "pgvector", matchIfMissing = true)
public class PgVectorStore { ... }
```

---

## Issue #3: Unused Database Table

### The Problem

Your `init.sql` creates a `document_chunks` table:

```sql
CREATE TABLE IF NOT EXISTS document_chunks (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    embedding vector(1536),
    ...
);
```

But Spring AI PGVector auto-creates and uses `vector_store` table. Your `document_chunks` table is **never used**.

### Why It Matters

1. **Misleading**: Future maintainers will wonder what it's for
2. **Wasted schema**: Extra table sitting empty
3. **Documentation mismatch**: Docs reference wrong table

### The Fix

**Option A: Delete the table definition from init.sql**

Keep only what you actually use:
```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ingested_sources (
    id UUID PRIMARY KEY,
    file_path VARCHAR(500) UNIQUE NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    file_size BIGINT,
    chunk_count INTEGER DEFAULT 0,
    ingested_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingested_sources_file_path ON ingested_sources (file_path);
```

**Option B: Use your own table instead of Spring AI's**

If you want full control, stop using `VectorStore.add()` and write directly to `document_chunks` with `JdbcTemplate`. But this is more work for little benefit.

---

## Issue #4: Missing File Deletion Detection

### The Problem

If you delete `docs/old-document.md`:
- Chunks from that file remain in the vector store forever
- `ingested_sources` still has a record for it
- Your data grows stale over time

### The Fix

Add a cleanup step to `IngestionService.ingestAllDocuments()`:

```java
@Transactional
public List<DocumentChunk> ingestAllDocuments() {
    Path docsDir = Path.of(docsPath);
    
    // Get current files on disk
    Set<String> currentFiles = new HashSet<>();
    try (Stream<Path> paths = Files.walk(docsDir)) {
        paths.filter(Files::isRegularFile)
             .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
             .forEach(p -> currentFiles.add(docsDir.relativize(p).toString()));
    }
    
    // Find files in DB that no longer exist
    List<IngestedSource> allSources = ingestedSourceRepository.findAll();
    for (IngestedSource source : allSources) {
        if (!currentFiles.contains(source.getFilePath())) {
            log.info("File {} no longer exists, removing from index", source.getFilePath());
            pgVectorStore.deleteBySourceFile(source.getFilePath());
            ingestedSourceRepository.delete(source);
        }
    }
    
    // ... rest of ingestion logic
}
```

---

## Issue #5: Potential Double Embedding

### The Problem

You embed chunks explicitly in `IngestionRunner`:

```java
List<DocumentChunk> embeddedChunks = embeddingService.embedChunks(chunks);  // Embedding #1
vectorStore.storeAll(embeddedChunks);
```

Then `PgVectorStore.storeAll()` creates Spring AI `Document` objects and calls `vectorStore.add()`:

```java
public void storeAll(List<DocumentChunk> chunks) {
    List<Document> documents = chunks.stream()
            .map(this::toDocument)
            .toList();
    vectorStore.add(documents);  // Does this embed again? Embedding #2?
}
```

### How to Verify

Check Spring AI's `PgVectorStore` source. If `add()` embeds content when the document doesn't have an embedding, and you're not passing the embedding through, you're paying twice.

### The Fix

**If Spring AI re-embeds:**

Skip your explicit embedding step. Let Spring AI handle it:

```java
// IngestionRunner.java - simplified
public void run(ApplicationArguments args) {
    List<DocumentChunk> chunks = ingestionService.ingestAllDocuments();
    if (chunks.isEmpty()) {
        log.info("No new or changed documents to ingest");
        return;
    }
    // Don't embed here - Spring AI does it
    vectorStore.storeAll(chunks);
}
```

**If Spring AI respects pre-computed embeddings:**

Pass the embedding through in `toDocument()`:

```java
private Document toDocument(DocumentChunk chunk) {
    Map<String, Object> metadata = Map.of(
            "sourceFile", chunk.sourceFile(),
            "chunkIndex", chunk.chunkIndex()
    );
    
    Document doc = new Document(chunk.id(), chunk.content(), metadata);
    if (chunk.hasEmbedding()) {
        // Set pre-computed embedding if Spring AI supports this
        doc.setEmbedding(toFloatArray(chunk.embedding()));
    }
    return doc;
}
```

---

## Issue #6: No Tests for New Functionality

### The Problem

Iteration 2 added:
- `FileHashService` - **no tests**
- `PgVectorStore` - **no tests**
- `IngestedSourceRepository` - **no tests**
- Incremental ingestion logic - **no tests**

You have tests only for chunking logic from Iteration 1.

### Why It Matters

1. **No regression protection**: Future changes might break these
2. **Interview red flag**: Principal engineers test their code
3. **Documentation gap**: Tests document expected behavior

### The Fixes

**FileHashServiceTest.java:**

```java
@ExtendWith(MockitoExtension.class)
class FileHashServiceTest {

    private FileHashService hashService = new FileHashService();

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
    void computeHash_returnsValidSha256() {
        String hash = hashService.computeHash("test");
        
        assertThat(hash).hasSize(64);  // SHA-256 = 64 hex chars
        assertThat(hash).matches("[a-f0-9]+");
    }

    @Test
    void computeHash_file_matchesContentHash(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "file content");
        
        String fileHash = hashService.computeHash(file);
        String contentHash = hashService.computeHash("file content");
        
        assertThat(fileHash).isEqualTo(contentHash);
    }
}
```

**IngestionServiceIncrementalTest.java:**

```java
@ExtendWith(MockitoExtension.class)
class IngestionServiceIncrementalTest {

    @Mock private FileHashService fileHashService;
    @Mock private IngestedSourceRepository repository;
    @Mock private IngestionProperties properties;
    
    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(fileHashService, repository, properties);
    }

    @Test
    void processFile_unchanged_skipsIngestion() throws IOException {
        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash123");
        when(repository.existsByFilePathAndFileHash("test.md", "hash123")).thenReturn(true);
        
        FileIngestionResult result = service.processFile(Path.of("docs/test.md"));
        
        assertThat(result.wasSkipped()).isTrue();
        assertThat(result.reason()).isEqualTo("unchanged");
    }

    @Test
    void processFile_changed_reingests() throws IOException {
        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("newHash");
        when(repository.existsByFilePathAndFileHash(anyString(), anyString())).thenReturn(false);
        when(repository.findByFilePath(anyString())).thenReturn(Optional.of(new IngestedSource()));
        
        // ... setup file content mock
        
        FileIngestionResult result = service.processFile(Path.of("docs/test.md"));
        
        assertThat(result.wasSkipped()).isFalse();
    }
}
```

**Integration Test with Testcontainers:**

```java
@SpringBootTest
@Testcontainers
class IngestionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private IngestionService ingestionService;
    
    @Autowired
    private IngestedSourceRepository repository;

    @Test
    void fullIngestionFlow_persistsAndDetectsChanges() {
        // First ingestion
        ingestionService.ingestAllDocuments();
        long initialCount = repository.count();
        assertThat(initialCount).isGreaterThan(0);
        
        // Second run - should skip all
        ingestionService.ingestAllDocuments();
        assertThat(repository.count()).isEqualTo(initialCount);
    }
}
```

---

## Issue #7: Configuration Contradiction

### The Problem

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.ai.vectorstore.pgvector.initialize-schema=true
```

You're telling Hibernate to **validate** (don't create, just check) while telling Spring AI to **create** the vector_store table. This works by accident because they manage different tables, but it's conceptually inconsistent.

### The Fix

Be explicit about your strategy:

**For development:**
```properties
spring.jpa.hibernate.ddl-auto=update
spring.ai.vectorstore.pgvector.initialize-schema=true
```

**For production:**
```properties
spring.jpa.hibernate.ddl-auto=validate
spring.ai.vectorstore.pgvector.initialize-schema=false
# Use Flyway or Liquibase for migrations
```

Add a comment explaining the choice:
```properties
# Schema management: 
# - JPA entities (ingested_sources) managed by Hibernate
# - Vector store table managed by Spring AI auto-init
# In production, replace with Flyway migrations
```

---

## Issue #8: Silent Failure on Startup

### The Problem

```java
} catch (Exception e) {
    log.error("Failed to ingest documents: {}", e.getMessage(), e);
}
```

If ingestion fails, the app starts anyway with an empty/stale index. Is this intentional?

### Why It Matters

- Users querying get no results or stale results
- Health endpoint shows "UP" but the service is broken
- Problem only discovered when queries fail

### The Fix

**Option A: Fail fast (recommended for this use case)**

```java
@Override
public void run(ApplicationArguments args) {
    log.info("Starting document ingestion...");
    
    List<DocumentChunk> chunks = ingestionService.ingestAllDocuments();
    
    if (chunks.isEmpty()) {
        log.info("No new or changed documents to ingest");
        return;
    }
    
    List<DocumentChunk> embeddedChunks = embeddingService.embedChunks(chunks);
    vectorStore.storeAll(embeddedChunks);
    
    log.info("Ingestion complete: {} new chunks indexed", embeddedChunks.size());
    // No try-catch: let Spring handle startup failure
}
```

**Option B: Custom health indicator**

```java
@Component
public class IngestionHealthIndicator implements HealthIndicator {
    
    private final AtomicBoolean ingestionHealthy = new AtomicBoolean(false);
    private String lastError;
    
    public void markHealthy() {
        ingestionHealthy.set(true);
        lastError = null;
    }
    
    public void markUnhealthy(String error) {
        ingestionHealthy.set(false);
        lastError = error;
    }
    
    @Override
    public Health health() {
        if (ingestionHealthy.get()) {
            return Health.up().build();
        }
        return Health.down()
                .withDetail("error", lastError)
                .build();
    }
}
```

---

## Summary Checklist

| # | Issue | Severity | Fix Effort |
|---|-------|----------|------------|
| 1 | Orphaned chunks on file change | CRITICAL | Medium |
| 2 | Dead InMemoryVectorStore code | Medium | Low |
| 3 | Unused document_chunks table | Low | Low |
| 4 | No file deletion detection | Medium | Medium |
| 5 | Potential double embedding | Medium | Low |
| 6 | No tests for new code | High | Medium |
| 7 | Configuration contradiction | Low | Low |
| 8 | Silent startup failure | Medium | Low |

### Priority Order

1. **Issue #1** - Fix the orphaned chunks bug (it breaks core functionality)
2. **Issue #6** - Add tests (proves the fix works, prevents regression)
3. **Issue #4** - Add file deletion detection (completes the incremental story)
4. **Issue #2** - Remove dead code (cleanup)
5. **Issue #3** - Remove unused table (cleanup)
6. **Issue #5** - Verify embedding behavior (performance/cost)
7. **Issue #8** - Improve error handling (production readiness)
8. **Issue #7** - Clean up configuration (clarity)

---

## Final Thought

The documentation for Iteration 2 is polished with PlantUML diagrams and detailed explanations. But interviewers will read your code, not your diagrams. A working implementation with tests beats beautiful documentation of broken code. Fix the bugs first, then update the docs.
