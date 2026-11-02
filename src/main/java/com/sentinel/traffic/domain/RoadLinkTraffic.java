package com.sentinel.traffic.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * ITS와 TOPIS로부터 수집·정규화한 도로 구간별 실시간 소통 상태 및 경로 좌표.
 */
public record RoadLinkTraffic(
        @NotNull TrafficSource source,
        @NotBlank String linkId,
        @NotBlank String roadName,
        @NotBlank String sectionName,
        @NotNull RoadType roadType,
        double speedKph,
        @NotNull CongestionLevel congestionLevel,
        @NotEmpty List<GeoPoint> geometry,
        @NotNull Instant observedAt
) {

    public static RoadLinkTraffic of(
            TrafficSource source,
            String linkId,
            String roadName,
            String sectionName,
            RoadType roadType,
            double rawSpeedKph,
            List<GeoPoint> geometry,
            Instant observedAt
    ) {
        CongestionLevel level = roadType.classify(rawSpeedKph);
        double normalizedSpeed = (level == CongestionLevel.UNKNOWN) ? 0.0 : rawSpeedKph;
        return new RoadLinkTraffic(
                source,
                linkId,
                roadName,
                sectionName,
                roadType,
                normalizedSpeed,
                level,
                List.copyOf(geometry),
                observedAt
        );
    }
}
