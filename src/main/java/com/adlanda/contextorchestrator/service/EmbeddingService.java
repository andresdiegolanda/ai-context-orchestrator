package com.adlanda.contextorchestrator.service;

import com.adlanda.contextorchestrator.model.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for generating vector embeddings from text.
 *
 * Uses Spring AI's EmbeddingModel to call OpenAI's embedding API.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Generates an embedding vector for the given text.
     *
     * @param text The text to embed
     * @return A list of doubles representing the embedding vector (1536 dimensions)
     */
    public List<Double> embed(String text) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
        float[] embedding = response.getResult().getOutput();
        return toDoubleList(embedding);
    }

    /**
     * Adds embeddings to a list of chunks.
     *
     * @param chunks Chunks without embeddings
     * @return Chunks with embeddings added
     */
    public List<DocumentChunk> embedChunks(List<DocumentChunk> chunks) {
        log.info("Generating embeddings for {} chunks...", chunks.size());

        List<DocumentChunk> embeddedChunks = chunks.stream()
                .map(chunk -> {
                    List<Double> embedding = embed(chunk.content());
                    return chunk.withEmbedding(embedding);
                })
                .toList();

        log.info("Generated {} embeddings", embeddedChunks.size());
        return embeddedChunks;
    }

    private List<Double> toDoubleList(float[] floats) {
        Double[] doubles = new Double[floats.length];
        for (int i = 0; i < floats.length; i++) {
            doubles[i] = (double) floats[i];
        }
        return List.of(doubles);
    }
}
