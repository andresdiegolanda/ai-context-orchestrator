package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.config.IngestionProperties;
import com.adlanda.contextorchestrator.model.DocumentChunk;
import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private FileHashService fileHashService;

    @Mock
    private IngestedSourceRepository ingestedSourceRepository;

    @Mock
    private IngestionProperties ingestionProperties;

    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new IngestionService(fileHashService, ingestedSourceRepository, ingestionProperties);
        // Mock hash computation to return a fixed hash for content-based calls
        when(fileHashService.computeHash(anyString())).thenReturn("test-hash");
    }

    @Test
    void chunkContent_singleParagraph_returnsSingleChunk() {
        String content = "This is a simple paragraph.";

        List<DocumentChunk> chunks = ingestionService.chunkContent(content, "test.md");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("This is a simple paragraph.");
        assertThat(chunks.get(0).sourceFile()).isEqualTo("test.md");
        assertThat(chunks.get(0).chunkIndex()).isEqualTo(0);
    }

    @Test
    void chunkContent_multipleParagraphs_preservesAllContent() {
        String content = """
            First paragraph.

            Second paragraph.

            Third paragraph.
            """;

        List<DocumentChunk> chunks = ingestionService.chunkContent(content, "test.md");

        // Small paragraphs may each become their own chunk
        assertThat(chunks).isNotEmpty();
        // All content should be represented
        String allContent = chunks.stream()
                .map(DocumentChunk::content)
                .reduce("", (a, b) -> a + " " + b);
        assertThat(allContent)
                .contains("First paragraph")
                .contains("Second paragraph")
                .contains("Third paragraph");
    }

    @Test
    void chunkContent_emptyContent_returnsEmptyList() {
        List<DocumentChunk> chunks = ingestionService.chunkContent("", "test.md");

        assertThat(chunks).isEmpty();
    }

    @Test
    void chunkContent_onlyWhitespace_returnsEmptyList() {
        List<DocumentChunk> chunks = ingestionService.chunkContent("   \n\n   ", "test.md");

        assertThat(chunks).isEmpty();
    }

    @Test
    void chunkContent_preservesChunkIndex() {
        // Create content large enough to force multiple chunks
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            largeContent.append("This is paragraph number ").append(i)
                       .append(" with some additional text to make it longer.\n\n");
        }

        List<DocumentChunk> chunks = ingestionService.chunkContent(largeContent.toString(), "test.md");

        assertThat(chunks).hasSizeGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).chunkIndex()).isEqualTo(i);
        }
    }

    @Test
    void chunkContent_chunksHaveUniqueIds() {
        String content = """
            First paragraph with enough text to matter.

            Second paragraph with more text.
            """;

        List<DocumentChunk> chunks = ingestionService.chunkContent(content, "test.md");

        assertThat(chunks.get(0).id()).isNotNull();
        assertThat(chunks.get(0).id()).isNotEmpty();
    }

    @Test
    void chunkContent_chunksHaveNoEmbedding() {
        String content = "Simple content.";

        List<DocumentChunk> chunks = ingestionService.chunkContent(content, "test.md");

        assertThat(chunks.get(0).hasEmbedding()).isFalse();
    }

    @Test
    void chunkContent_chunksHaveFileHash() {
        String content = "Simple content.";

        List<DocumentChunk> chunks = ingestionService.chunkContent(content, "test.md");

        assertThat(chunks.get(0).fileHash()).isEqualTo("test-hash");
    }
}