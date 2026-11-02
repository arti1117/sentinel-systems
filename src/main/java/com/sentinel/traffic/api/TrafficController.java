package com.sentinel.traffic.api;

import com.sentinel.traffic.domain.CongestionLevel;
import com.sentinel.traffic.domain.TrafficOverviewResponse;
import com.sentinel.traffic.domain.TrafficSource;
import com.sentinel.traffic.service.TrafficStatusService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 REST Controller: Real-time road traffic status (ITS + TOPIS) and health check.
 */
@RestController
public class TrafficController {

    private final TrafficStatusService trafficStatusService;

    public TrafficController(TrafficStatusService trafficStatusService) {
        this.trafficStatusService = trafficStatusService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "runtime", "java-" + Runtime.version().feature()
        );
    }

    @GetMapping("/api/v1/traffic/overview")
    public TrafficOverviewResponse getOverview(
            @RequestParam(required = false) TrafficSource source,
            @RequestParam(required = false) CongestionLevel congestion
    ) {
        return trafficStatusService.getOverview(source, congestion);
    }

    @PostMapping("/api/v1/traffic/refresh")
    public Map<String, Object> refreshFeeds() {
        int updated = trafficStatusService.refreshAllFeeds();
        return Map.of("updatedCount", updated);
    }
}
