package com.adlanda.contextorchestrator.controller;

import com.adlanda.contextorchestrator.model.QueryRequest;
import com.adlanda.contextorchestrator.model.QueryResponse;
import com.adlanda.contextorchestrator.service.RetrievalService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for querying the context index.
 */
@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private final RetrievalService retrievalService;

    public QueryController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /**
     * Query for relevant context based on a question.
     *
     * @param request The query request containing the question
     * @return QueryResponse with matched chunks and metadata
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = retrievalService.query(request.question(), request.maxResults());
        return ResponseEntity.ok(response);
    }

    /**
     * Get index statistics.
     */
    @GetMapping("/sources")
    public ResponseEntity<Map<String, Object>> getSources() {
        return ResponseEntity.ok(Map.of(
                "totalChunks", retrievalService.getIndexSize(),
                "status", retrievalService.getIndexSize() > 0 ? "indexed" : "empty"
        ));
    }
}
