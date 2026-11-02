package com.sentinel.traffic.api;

import com.sentinel.traffic.domain.CongestionLevel;
import com.sentinel.traffic.domain.TrafficOverviewResponse;
import com.sentinel.traffic.domain.TrafficSource;
import com.sentinel.traffic.service.TrafficStatusService;
import com.sentinel.traffic.service.TrafficStatusService.ManualRefreshSummary;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1단계(도로현황) REST 컨트롤러: 실시간 도로 소통 요약 조회, 수동 오픈API 새로고침, 서버 상태 확인 API.
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
    public ManualRefreshSummary refreshFeeds(
            @RequestParam(required = false) TrafficSource source,
            @RequestParam(required = false) String linkId
    ) {
        return trafficStatusService.refreshLiveFeeds(source, linkId);
    }
}
