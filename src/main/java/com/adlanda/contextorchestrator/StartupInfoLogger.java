package com.adlanda.contextorchestrator;

import com.adlanda.contextorchestrator.repository.InMemoryVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2) // Run after IngestionRunner
public class StartupInfoLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    private final InMemoryVectorStore vectorStore;

    @Value("${server.port:8080}")
    private int port;

    @Value("${info.app.version:0.0.1-SNAPSHOT}")
    private String version;

    public StartupInfoLogger(InMemoryVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("""

            AI Context Orchestrator v{}
            Index: {} chunks

            API Endpoints:
              GET  http://localhost:{}/api/v1
              POST http://localhost:{}/api/v1/query
              GET  http://localhost:{}/api/v1/sources

            Health:
              GET  http://localhost:{}/actuator/health
            """,
            version, vectorStore.size(), port, port, port, port
        );
    }
}
