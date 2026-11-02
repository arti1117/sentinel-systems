package com.sentinel.traffic.domain;

import java.util.List;

/**
 * Aggregated real-time road status summary and link list for the control board.
 */
public record TrafficOverviewResponse(
        int totalLinks,
        int smoothCount,
        int slowCount,
        int congestedCount,
        int unknownCount,
        double averageSpeedKph,
        List<RoadLinkTraffic> links
) {
}
