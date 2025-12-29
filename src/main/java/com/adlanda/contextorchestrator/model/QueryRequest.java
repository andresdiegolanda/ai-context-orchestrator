package com.adlanda.contextorchestrator.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request body for the query endpoint.
 */
public record QueryRequest(
        @NotBlank(message = "Question is required")
        String question,

        @Min(1) @Max(20)
        Integer maxResults
) {
    public QueryRequest {
        if (maxResults == null) {
            maxResults = 5;
        }
    }
}
