package com.sentinel.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinel.ingestion.StateSynchronizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemController.class)
@Import(StateSynchronizer.class)
@ActiveProfiles("test")
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturnsOkAndJava21Runtime() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.runtime").value("java-21"));
    }

    @Test
    void getLinkStateReturnsDefaultUnknownSnapshot() throws Exception {
        mockMvc.perform(get("/api/v1/links/LINK-SEOUL-001/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value("LINK-SEOUL-001"))
                .andExpect(jsonPath("$.trafficStatus").value("UNKNOWN_TIMEOUT"));
    }
}
