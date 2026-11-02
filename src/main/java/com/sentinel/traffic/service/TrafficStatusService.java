package com.sentinel.traffic.service;

import com.sentinel.traffic.client.TrafficFeedClient;
import com.sentinel.traffic.domain.CongestionLevel;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficOverviewResponse;
import com.sentinel.traffic.domain.TrafficSource;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Phase 1 Service: Collects, deduplicates, and aggregates real-time road traffic status
 * across ITS (national highways/roads) and TOPIS (Seoul urban highways/arterials).
 */
@Service
public class TrafficStatusService {

    private final List<TrafficFeedClient> feedClients;
    private final Map<LinkKey, RoadLinkTraffic> latestByLink = new ConcurrentHashMap<>();

    public record LinkKey(TrafficSource source, String linkId) {
    }

    public TrafficStatusService(List<TrafficFeedClient> feedClients) {
        this.feedClients = feedClients;
    }

    @PostConstruct
    public void init() {
        refreshAllFeeds();
    }

    /**
     * Poll all configured traffic feed adapters (ITS + TOPIS) and merge newer observations.
     */
    public int refreshAllFeeds() {
        int updated = 0;
        for (TrafficFeedClient client : feedClients) {
            for (RoadLinkTraffic item : client.fetchLatestTraffic()) {
                if (upsertLinkTraffic(item)) {
                    updated++;
                }
            }
        }
        return updated;
    }

    /**
     * Upsert a single road link observation, ignoring out-of-order (older) packets.
     */
    public boolean upsertLinkTraffic(RoadLinkTraffic incoming) {
        LinkKey key = new LinkKey(incoming.source(), incoming.linkId());
        boolean[] applied = {false};
        latestByLink.compute(key, (k, existing) -> {
            if (existing != null && incoming.observedAt().isBefore(existing.observedAt())) {
                return existing;
            }
            applied[0] = true;
            return incoming;
        });
        return applied[0];
    }

    /**
     * Build a filtered traffic overview sorted by congestion severity (CONGESTED -> SLOW -> SMOOTH)
     * and ascending speed.
     */
    public TrafficOverviewResponse getOverview(
            TrafficSource sourceFilter,
            CongestionLevel congestionFilter
    ) {
        List<RoadLinkTraffic> filtered = latestByLink.values().stream()
                .filter(item -> sourceFilter == null || item.source() == sourceFilter)
                .filter(item -> congestionFilter == null || item.congestionLevel() == congestionFilter)
                .sorted(Comparator
                        .comparingInt((RoadLinkTraffic item) -> severityRank(item.congestionLevel()))
                        .thenComparingDouble(RoadLinkTraffic::speedKph)
                        .thenComparing(RoadLinkTraffic::linkId))
                .toList();

        int smooth = 0;
        int slow = 0;
        int congested = 0;
        int unknown = 0;
        double speedSum = 0.0;
        int validSpeedCount = 0;

        for (RoadLinkTraffic item : filtered) {
            switch (item.congestionLevel()) {
                case SMOOTH -> smooth++;
                case SLOW -> slow++;
                case CONGESTED -> congested++;
                case UNKNOWN -> unknown++;
            }
            if (item.congestionLevel() != CongestionLevel.UNKNOWN) {
                speedSum += item.speedKph();
                validSpeedCount++;
            }
        }

        double avgSpeed = (validSpeedCount > 0)
                ? Math.round((speedSum / validSpeedCount) * 10.0) / 10.0
                : 0.0;

        return new TrafficOverviewResponse(
                filtered.size(),
                smooth,
                slow,
                congested,
                unknown,
                avgSpeed,
                filtered
        );
    }

    private static int severityRank(CongestionLevel level) {
        return switch (level) {
            case CONGESTED -> 0;
            case SLOW -> 1;
            case SMOOTH -> 2;
            case UNKNOWN -> 3;
        };
    }
}
