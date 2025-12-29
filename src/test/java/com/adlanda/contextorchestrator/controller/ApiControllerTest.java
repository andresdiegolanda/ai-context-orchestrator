package com.adlanda.contextorchestrator.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApiController.class)
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootEndpoint_returnsServiceInfo() throws Exception {
        mockMvc.perform(get("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("AI Context Orchestrator"))
                .andExpect(jsonPath("$.endpoints").exists());
    }

    @Test
    void rootEndpoint_includesAllEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoints.query").exists())
                .andExpect(jsonPath("$.endpoints.ingest").exists())
                .andExpect(jsonPath("$.endpoints.sources").exists())
                .andExpect(jsonPath("$.endpoints.health").exists());
    }
}
