package com.adlanda.contextorchestrator;

import com.adlanda.contextorchestrator.repository.IngestedSourceRepository;
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

    private final IngestedSourceRepository ingestedSourceRepository;

    @Value("${server.port:8080}")
    private int port;

    @Value("${info.app.version:0.0.1-SNAPSHOT}")
    private String version;

    public StartupInfoLogger(IngestedSourceRepository ingestedSourceRepository) {
        this.ingestedSourceRepository = ingestedSourceRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        long totalChunks = ingestedSourceRepository.sumChunkCount();
        long totalFiles = ingestedSourceRepository.count();
        
        log.info("""

            AI Context Orchestrator v{}
            Index: {} files, {} chunks

            API Endpoints:
              GET  http://localhost:{}/api/v1
              POST http://localhost:{}/api/v1/query
              GET  http://localhost:{}/api/v1/sources

            Health:
              GET  http://localhost:{}/actuator/health
            """,
            version, totalFiles, totalChunks, port, port, port, port
        );
    }
}
