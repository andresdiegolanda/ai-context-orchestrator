package com.adlanda.contextorchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.openai.api-key=test-key",
    "orchestrator.docs.path=./docs",
    "orchestrator.ingestion.enabled=false"  // Disable ingestion during tests
})
class OrchestratorApplicationTests {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public EmbeddingModel embeddingModel() {
            EmbeddingModel mockModel = mock(EmbeddingModel.class);
            float[] mockEmbedding = new float[1536];
            when(mockModel.embedForResponse(anyList()))
                    .thenReturn(new EmbeddingResponse(List.of(new Embedding(mockEmbedding, 0))));
            return mockModel;
        }
    }

    @Test
    void contextLoads() {
        // Context loads successfully with mocked EmbeddingModel
    }
}
