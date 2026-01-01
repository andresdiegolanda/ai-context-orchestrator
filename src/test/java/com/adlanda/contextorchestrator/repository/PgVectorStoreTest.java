package com.adlanda.contextorchestrator.repository;

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

/**
 * Unit tests for PgVectorStore.
 * Tests vector storage operations and metadata-based deletion.
 */
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
    void deleteBySourceFile_handlesSpecialCharactersInPath() {
        String sourceFile = "docs/path with spaces/file.md";
        when(jdbcTemplate.update(anyString(), eq(sourceFile))).thenReturn(2);

        int deleted = pgVectorStore.deleteBySourceFile(sourceFile);

        assertThat(deleted).isEqualTo(2);
        verify(jdbcTemplate).update(anyString(), eq(sourceFile));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeAll_convertsChunksToDocuments() {
        DocumentChunk chunk = DocumentChunk.withoutEmbedding(
                UUID.randomUUID().toString(),
                "Test content",
                "docs/test.md",
                0,
                "abc123hash"
        );

        pgVectorStore.storeAll(List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getContent()).isEqualTo("Test content");
        assertThat(documents.get(0).getMetadata()).containsEntry("sourceFile", "docs/test.md");
        assertThat(documents.get(0).getMetadata()).containsEntry("chunkIndex", 0);
    }

    @Test
    void storeAll_emptyList_doesNothing() {
        pgVectorStore.storeAll(List.of());

        verify(vectorStore, never()).add(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeAll_multipleChunks_storesAll() {
        List<DocumentChunk> chunks = List.of(
                DocumentChunk.withoutEmbedding("id1", "Content 1", "file1.md", 0, "hash1"),
                DocumentChunk.withoutEmbedding("id2", "Content 2", "file1.md", 1, "hash1"),
                DocumentChunk.withoutEmbedding("id3", "Content 3", "file2.md", 0, "hash2")
        );

        pgVectorStore.storeAll(chunks);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeAll_preservesChunkMetadata() {
        DocumentChunk chunk = DocumentChunk.withoutEmbedding(
                "test-id",
                "Test content",
                "docs/guide.md",
                5,
                "somehash123"
        );

        pgVectorStore.storeAll(List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertThat(doc.getId()).isEqualTo("test-id");
        assertThat(doc.getMetadata())
                .containsEntry("sourceFile", "docs/guide.md")
                .containsEntry("chunkIndex", 5)
                .containsEntry("fileHash", "somehash123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeAll_handlesNullFileHash() {
        DocumentChunk chunk = DocumentChunk.withoutEmbedding(
                "test-id",
                "Test content",
                "docs/test.md",
                0
        );

        pgVectorStore.storeAll(List.of(chunk));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        Document doc = captor.getValue().get(0);
        assertThat(doc.getMetadata()).containsEntry("fileHash", "");
    }
}
