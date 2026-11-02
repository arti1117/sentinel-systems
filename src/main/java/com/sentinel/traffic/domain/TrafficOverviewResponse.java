package com.sentinel.traffic.domain;

import java.time.Instant;
import java.util.List;

/**
 * 실시간 도로 소통 현황 요약 통계, 쿼터 사용량 및 구간 목록 응답 DTO.
 */
public record TrafficOverviewResponse(
        int totalLinks,
        int smoothCount,
        int slowCount,
        int congestedCount,
        int unknownCount,
        double averageSpeedKph,
        int sessionApiCallsUsed,
        Instant lastLiveRefreshAt,
        List<RoadLinkTraffic> links
) {
}
