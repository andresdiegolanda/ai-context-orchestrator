package com.adlanda.contextorchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for document ingestion.
 *
 * Maps to properties prefixed with 'orchestrator.ingestion' in application.properties.
 */
@Component
@ConfigurationProperties(prefix = "orchestrator.ingestion")
public class IngestionProperties {

    /**
     * Whether ingestion is enabled at startup.
     * When false, no documents will be ingested (useful for testing).
     */
    private boolean enabled = true;

    /**
     * Whether to enable incremental ingestion (skip unchanged files).
     * When true, files are only re-ingested if their content hash has changed.
     */
    private boolean incremental = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public void setIncremental(boolean incremental) {
        this.incremental = incremental;
    }
}