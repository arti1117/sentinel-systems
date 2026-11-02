package com.sentinel.domain;

import java.time.Instant;

/**
 * Synchronized point-in-time state snapshot of a single road link across heterogeneous feeds.
 */
public record LinkStateSnapshot(
        String linkId,
        Double lastTrafficSpeedKph,
        Instant lastTrafficObservedAt,
        FeedStatus trafficStatus,
        Double lastPrecipitationMmHr,
        Double lastRoadSurfaceTempC,
        Integer lastVisibilityM,
        Instant lastWeatherObservedAt,
        FeedStatus weatherStatus,
        long appliedEventCount,
        long ignoredDuplicateCount,
        long rejectedOutOfOrderCount
) {

    public static LinkStateSnapshot empty(String linkId) {
        return new LinkStateSnapshot(
                linkId,
                null,
                null,
                FeedStatus.UNKNOWN_TIMEOUT,
                null,
                null,
                null,
                null,
                FeedStatus.UNKNOWN_TIMEOUT,
                0L,
                0L,
                0L
        );
    }
}
