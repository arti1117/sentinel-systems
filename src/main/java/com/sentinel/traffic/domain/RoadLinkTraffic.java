package com.sentinel.traffic.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/**
 * Unified road section traffic status with WGS84 polyline geometry normalized across ITS and TOPIS.
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
