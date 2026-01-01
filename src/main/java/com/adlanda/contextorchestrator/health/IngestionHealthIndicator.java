package com.adlanda.contextorchestrator.health;

import com.adlanda.contextorchestrator.service.IngestionService.IngestionSummary;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health indicator for the document ingestion process.
 * 
 * Reports the status of the last ingestion run, including:
 * - Whether ingestion completed successfully
 * - Number of files tracked and chunks indexed
 * - Timestamp of last run
 * - Error details if ingestion failed
 */
@Component
public class IngestionHealthIndicator implements HealthIndicator {

    private final AtomicReference<HealthState> state = new AtomicReference<>(
            new HealthState(false, null, "Ingestion not yet run", null)
    );

    /**
     * Marks the ingestion as healthy with the given summary.
     */
    public void markHealthy(IngestionSummary summary) {
        state.set(new HealthState(
                true,
                summary,
                null,
                Instant.now()
        ));
    }

    /**
     * Marks the ingestion as unhealthy with the given error message.
     */
    public void markUnhealthy(String error) {
        state.set(new HealthState(
                false,
                null,
                error,
                Instant.now()
        ));
    }

    @Override
    public Health health() {
        HealthState current = state.get();

        if (current.healthy()) {
            Health.Builder builder = Health.up()
                    .withDetail("lastRun", current.timestamp() != null ? current.timestamp().toString() : "never");
            
            if (current.summary() != null) {
                builder.withDetail("filesTracked", 
                        current.summary().skippedFiles() + current.summary().processedFiles())
                       .withDetail("filesProcessed", current.summary().processedFiles())
                       .withDetail("filesSkipped", current.summary().skippedFiles())
                       .withDetail("filesDeleted", current.summary().deletedFiles())
                       .withDetail("chunksIndexed", current.summary().totalChunks());
            }
            
            return builder.build();
        }

        return Health.down()
                .withDetail("error", current.error())
                .withDetail("lastAttempt", current.timestamp() != null ? current.timestamp().toString() : "never")
                .build();
    }

    /**
     * Internal state holder for thread-safe health updates.
     */
    private record HealthState(
            boolean healthy,
            IngestionSummary summary,
            String error,
            Instant timestamp
    ) {}
}
