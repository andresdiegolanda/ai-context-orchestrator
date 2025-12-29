package com.adlanda.contextorchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Context Orchestrator - Main Application
 *
 * Transforms scattered project knowledge (markdown files, code, documentation)
 * into optimized context documents for AI assistants like Claude and GitHub Copilot.
 *
 * This application uses:
 * - Spring Boot 3.3 with Java 21 (virtual threads enabled)
 * - Spring AI for embedding generation via OpenAI
 * - PGVector for vector storage and similarity search (later iterations)
 *
 * @see <a href="https://docs.spring.io/spring-ai/reference/">Spring AI Documentation</a>
 */
@SpringBootApplication
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
