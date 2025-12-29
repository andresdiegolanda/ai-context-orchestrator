package com.adlanda.contextorchestrator.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Root API controller providing endpoint discovery.
 *
 * Health checks are handled by Spring Actuator at /actuator/health.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    @Value("${info.app.version:0.0.1-SNAPSHOT}")
    private String appVersion;

    /**
     * Root endpoint with API documentation links.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
                "service", "AI Context Orchestrator",
                "version", appVersion,
                "endpoints", Map.of(
                        "query", "POST /api/v1/query - Query for relevant context",
                        "ingest", "POST /api/v1/ingest - Trigger document ingestion",
                        "sources", "GET /api/v1/sources - List ingested sources",
                        "health", "GET /actuator/health - Health check",
                        "info", "GET /actuator/info - Application info"
                )
        ));
    }
}
