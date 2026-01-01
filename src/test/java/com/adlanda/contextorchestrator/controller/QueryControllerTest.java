package com.adlanda.contextorchestrator.controller;

import com.adlanda.contextorchestrator.model.QueryResponse;
import com.adlanda.contextorchestrator.model.QueryResult;
import com.adlanda.contextorchestrator.service.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QueryController.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RetrievalService retrievalService;

    @Test
    void query_validRequest_returnsResults() throws Exception {
        QueryResponse response = new QueryResponse(
                List.of(new QueryResult("test content", "test.md", 0, 0.95)),
                10,
                50
        );
        when(retrievalService.query(anyString(), anyInt())).thenReturn(response);

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"question": "What is virtual threading?"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results[0].content").value("test content"))
                .andExpect(jsonPath("$.results[0].score").value(0.95))
                .andExpect(jsonPath("$.totalChunks").value(10))
                .andExpect(jsonPath("$.queryTimeMs").value(50));
    }

    @Test
    void query_withMaxResults_passesToService() throws Exception {
        QueryResponse response = new QueryResponse(List.of(), 0, 10);
        when(retrievalService.query("test", 3)).thenReturn(response);

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"question": "test", "maxResults": 3}
                            """))
                .andExpect(status().isOk());
    }

    @Test
    void query_emptyQuestion_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"question": ""}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void query_missingQuestion_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSources_returnsIndexStats() throws Exception {
        when(retrievalService.getIndexSize()).thenReturn(42L);

        mockMvc.perform(get("/api/v1/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunks").value(42))
                .andExpect(jsonPath("$.status").value("indexed"));
    }

    @Test
    void getSources_emptyIndex_returnsEmptyStatus() throws Exception {
        when(retrievalService.getIndexSize()).thenReturn(0L);

        mockMvc.perform(get("/api/v1/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChunks").value(0))
                .andExpect(jsonPath("$.status").value("empty"));
    }
}
