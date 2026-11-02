package com.sentinel.traffic;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinel.traffic.api.TrafficController;
import com.sentinel.traffic.client.ItsTrafficFeedClient;
import com.sentinel.traffic.client.RoadNetworkCatalog;
import com.sentinel.traffic.client.TopisTrafficFeedClient;
import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.service.TrafficStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrafficController.class)
@Import({
    TrafficStatusService.class,
    RoadNetworkCatalog.class,
    ItsTrafficFeedClient.class,
    TopisTrafficFeedClient.class
})
@EnableConfigurationProperties(PublicApiProperties.class)
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
    void overviewEndpointReturns21RoadSectionsWithCurveGeometry() throws Exception {
        mockMvc.perform(get("/api/v1/traffic/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").value(21))
                .andExpect(jsonPath("$.unknownCount").value(0))
                .andExpect(jsonPath("$.sessionApiCallsUsed").value(0))
                .andExpect(jsonPath("$.lastLiveRefreshAt").isNotEmpty())
                .andExpect(jsonPath("$.links[0].geometry[0].lat").isNumber())
                .andExpect(jsonPath("$.links[0].geometry[0].lng").isNumber());
    }

    @Test
    void overviewEndpointSupportsBboxAndZoomSpatialQuery() throws Exception {
        mockMvc.perform(get("/api/v1/traffic/overview")
                        .param("bbox", "127.01,37.48,127.07,37.52")
                        .param("zoom", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLinks").isNumber());
    }

    @Test
    void manualRefreshEndpointUpdatesSelectedLink() throws Exception {
        mockMvc.perform(post("/api/v1/traffic/refresh")
                        .param("linkId", "TOPIS-ART-101-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));
    }
}
