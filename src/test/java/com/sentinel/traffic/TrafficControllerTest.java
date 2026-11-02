package com.sentinel.traffic;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinel.traffic.api.TrafficController;
import com.sentinel.traffic.client.ItsTrafficFeedClient;
import com.sentinel.traffic.client.TopisTrafficFeedClient;
import com.sentinel.traffic.service.TrafficStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrafficController.class)
@Import({TrafficStatusService.class, ItsTrafficFeedClient.class, TopisTrafficFeedClient.class})
@ActiveProfiles("test")
class TrafficControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.runtime").value("java-21"));
    }

    @Test
    void overviewEndpointReturnsCombinedItsAndTopisTraffic() throws Exception {
        mockMvc.perform(get("/api/v1/traffic/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").value(11))
                .andExpect(jsonPath("$.links[0].congestionLevel").value("CONGESTED"));
    }

    @Test
    void overviewEndpointFiltersBySourceAndCongestion() throws Exception {
        mockMvc.perform(get("/api/v1/traffic/overview")
                        .param("source", "TOPIS")
                        .param("congestion", "CONGESTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").value(2))
                .andExpect(jsonPath("$.links[0].source").value("TOPIS"))
                .andExpect(jsonPath("$.links[0].congestionLevel").value("CONGESTED"));
    }
}
