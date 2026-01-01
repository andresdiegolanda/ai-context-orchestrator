package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.config.IngestionProperties;
import com.adlanda.contextorchestrator.entity.IngestedSource;
import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import com.adlanda.contextorchestrator.repository.PgVectorStore;
import com.adlanda.contextorchestrator.service.IngestionService.FileIngestionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IngestionService incremental ingestion functionality.
 * Tests file change detection, orphan cleanup, and deletion detection.
 */
@ExtendWith(MockitoExtension.class)
class IngestionServiceIncrementalTest {

    @Mock
    private FileHashService fileHashService;
    
    @Mock
    private IngestedSourceRepository repository;
    
    @Mock
    private IngestionProperties properties;
    
    @Mock
    private PgVectorStore pgVectorStore;

    @TempDir
    Path tempDir;

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(
                fileHashService, repository, properties, pgVectorStore
        );
        ReflectionTestUtils.setField(service, "docsPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "maxTokens", 512);
    }

    @Test
    void processFile_unchanged_skipsIngestion() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Test Content\n\nSome paragraph.");

        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash123");
        when(fileHashService.getFileSize(any(Path.class))).thenReturn(100L);
        when(repository.existsByFilePathAndFileHash("test.md", "hash123")).thenReturn(true);

        // Execute
        FileIngestionResult result = service.processFile(testFile);

        // Verify
        assertThat(result.wasSkipped()).isTrue();
        assertThat(result.reason()).isEqualTo("unchanged");
        verify(pgVectorStore, never()).deleteBySourceFile(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void processFile_changed_deletesOldChunksAndReingests() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Updated Content\n\nNew paragraph.");

        IngestedSource existingSource = new IngestedSource();
        existingSource.setFilePath("test.md");
        existingSource.setFileHash("oldHash");
        existingSource.setChunkCount(3);

        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("newHash");
        when(fileHashService.getFileSize(any(Path.class))).thenReturn(200L);
        when(repository.existsByFilePathAndFileHash(anyString(), anyString())).thenReturn(false);
        when(repository.findByFilePath("test.md")).thenReturn(Optional.of(existingSource));

        // Execute
        FileIngestionResult result = service.processFile(testFile);

        // Verify old chunks were deleted
        verify(pgVectorStore).deleteBySourceFile("test.md");
        verify(repository).delete(existingSource);
        assertThat(result.wasSkipped()).isFalse();
        assertThat(result.chunks()).isNotEmpty();
    }

    @Test
    void processFile_newFile_ingestsWithoutDeletion() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("new-file.md");
        Files.writeString(testFile, "# New Document\n\nFresh content here.");

        when(properties.isIncremental()).thenReturn(true);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash456");
        when(fileHashService.getFileSize(any(Path.class))).thenReturn(150L);
        when(repository.existsByFilePathAndFileHash(anyString(), anyString())).thenReturn(false);
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());

        // Execute
        FileIngestionResult result = service.processFile(testFile);

        // Verify no deletion attempted for new file
        verify(pgVectorStore, never()).deleteBySourceFile(anyString());
        verify(repository, never()).delete(any(IngestedSource.class));
        assertThat(result.wasSkipped()).isFalse();
        assertThat(result.chunks()).isNotEmpty();
        
        // Verify new source was saved
        verify(repository).save(any(IngestedSource.class));
    }

    @Test
    void processFile_nonIncrementalMode_alwaysProcesses() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("test.md");
        Files.writeString(testFile, "# Content\n\nParagraph text.");

        when(properties.isIncremental()).thenReturn(false);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash789");
        when(fileHashService.getFileSize(any(Path.class))).thenReturn(100L);

        // Execute
        FileIngestionResult result = service.processFile(testFile);

        // Verify - should process without checking existing records
        verify(repository, never()).existsByFilePathAndFileHash(anyString(), anyString());
        verify(repository, never()).findByFilePath(anyString());
        assertThat(result.wasSkipped()).isFalse();
    }

    @Test
    void processFile_createsCorrectChunks() throws IOException {
        // Setup
        Path testFile = tempDir.resolve("multi-paragraph.md");
        Files.writeString(testFile, """
                # Title
                
                First paragraph with some content.
                
                Second paragraph with more content.
                
                Third paragraph to test chunking.
                """);

        when(properties.isIncremental()).thenReturn(false);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("testhash");
        when(fileHashService.getFileSize(any(Path.class))).thenReturn(200L);

        // Execute
        FileIngestionResult result = service.processFile(testFile);

        // Verify chunks were created
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().get(0).sourceFile()).isEqualTo("multi-paragraph.md");
        assertThat(result.filePath()).isEqualTo("multi-paragraph.md");
    }

    @Test
    void processFile_normalizesPathSeparators() throws IOException {
        // Setup - create nested file
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);
        Path testFile = subDir.resolve("nested.md");
        Files.writeString(testFile, "# Nested\n\nContent.");

        when(properties.isIncremental()).thenReturn(false);
        when(fileHashService.computeHash(any(Path.class))).thenReturn("hash");
        when(fileHashService.getFileSize(any(Path.class))).thenReturn(50L);

        // Execute
        FileIngestionResult result = service.processFile(testFile);

        // Verify path uses forward slashes
        assertThat(result.filePath()).isEqualTo("subdir/nested.md");
        assertThat(result.chunks().get(0).sourceFile()).isEqualTo("subdir/nested.md");
    }

    @Test
    void chunkContent_splitsCorrectly() {
        // Setup - no mocking needed as we're passing the hash directly
        String content = """
                # Header
                
                Paragraph one with content.
                
                Paragraph two with more content.
                """;

        // Execute using reflection to access package-private method
        var chunks = service.chunkContent(content, "test.md", "hash123");

        // Verify
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).sourceFile()).isEqualTo("test.md");
        assertThat(chunks.get(0).fileHash()).isEqualTo("hash123");
    }
}
