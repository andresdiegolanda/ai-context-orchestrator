package com.adlanda.contextorchestrator.repository;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryVectorStoreTest {

    private InMemoryVectorStore vectorStore;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryVectorStore();
    }

    @Test
    void store_chunkWithEmbedding_storesSuccessfully() {
        DocumentChunk chunk = createChunkWithEmbedding("1", "test content", List.of(1.0, 0.0, 0.0));

        vectorStore.store(chunk);

        assertThat(vectorStore.size()).isEqualTo(1);
    }

    @Test
    void store_chunkWithoutEmbedding_throwsException() {
        DocumentChunk chunk = DocumentChunk.withoutEmbedding("1", "test", "file.md", 0);

        assertThatThrownBy(() -> vectorStore.store(chunk))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without embedding");
    }

    @Test
    void findSimilar_identicalVector_returnsHighScore() {
        List<Double> embedding = List.of(1.0, 0.0, 0.0);
        DocumentChunk chunk = createChunkWithEmbedding("1", "test", embedding);
        vectorStore.store(chunk);

        List<InMemoryVectorStore.ScoredChunk> results = vectorStore.findSimilar(embedding, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isCloseTo(1.0, within(0.001));
    }

    @Test
    void findSimilar_orthogonalVector_returnsZeroScore() {
        DocumentChunk chunk = createChunkWithEmbedding("1", "test", List.of(1.0, 0.0, 0.0));
        vectorStore.store(chunk);

        List<Double> orthogonal = List.of(0.0, 1.0, 0.0);
        List<InMemoryVectorStore.ScoredChunk> results = vectorStore.findSimilar(orthogonal, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isCloseTo(0.0, within(0.001));
    }

    @Test
    void findSimilar_multipleChunks_returnsSortedByScore() {
        vectorStore.store(createChunkWithEmbedding("1", "first", List.of(1.0, 0.0, 0.0)));
        vectorStore.store(createChunkWithEmbedding("2", "second", List.of(0.7, 0.7, 0.0)));
        vectorStore.store(createChunkWithEmbedding("3", "third", List.of(0.0, 1.0, 0.0)));

        List<Double> query = List.of(1.0, 0.0, 0.0);
        List<InMemoryVectorStore.ScoredChunk> results = vectorStore.findSimilar(query, 5);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).chunk().content()).isEqualTo("first");
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
        assertThat(results.get(1).score()).isGreaterThan(results.get(2).score());
    }

    @Test
    void findSimilar_limitsResults() {
        for (int i = 0; i < 10; i++) {
            vectorStore.store(createChunkWithEmbedding(String.valueOf(i), "chunk " + i,
                    List.of((double) i / 10, 0.5, 0.5)));
        }

        List<InMemoryVectorStore.ScoredChunk> results = vectorStore.findSimilar(List.of(1.0, 0.5, 0.5), 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void clear_removesAllChunks() {
        vectorStore.store(createChunkWithEmbedding("1", "test", List.of(1.0, 0.0, 0.0)));
        assertThat(vectorStore.size()).isEqualTo(1);

        vectorStore.clear();

        assertThat(vectorStore.size()).isEqualTo(0);
    }

    private DocumentChunk createChunkWithEmbedding(String id, String content, List<Double> embedding) {
        return new DocumentChunk(id, content, "test.md", 0, null, embedding);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
