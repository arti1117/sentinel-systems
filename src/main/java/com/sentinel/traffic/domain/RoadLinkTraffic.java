package com.sentinel.traffic.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Unified road section traffic status normalized across ITS and TOPIS feeds.
 */
public record RoadLinkTraffic(
        @NotNull TrafficSource source,
        @NotBlank String linkId,
        @NotBlank String roadName,
        @NotBlank String sectionName,
        @NotNull RoadType roadType,
        double speedKph,
        @NotNull CongestionLevel congestionLevel,
        @NotNull Instant observedAt
) {

    public static RoadLinkTraffic of(
            TrafficSource source,
            String linkId,
            String roadName,
            String sectionName,
            RoadType roadType,
            double rawSpeedKph,
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
                observedAt
        );
    }
}
