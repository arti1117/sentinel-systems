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
 * 1단계(도로현황) 서비스: 국토부 ITS와 서울시 TOPIS 실시간 소통 데이터를 통합 수집·정규화하고 현황 요약을 제공한다.
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
     * 등록된 모든 교통 데이터 피드(ITS, TOPIS)를 호출해 최신 소통 현황을 갱신한다.
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
     * 단일 도로 구간의 소통 현황을 갱신한다. 기존 관측 시각보다 과거인 지연 데이터는 무시한다.
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
     * 출처 및 소통 등급 조건으로 필터링하고, 정체 심각도(정체 -> 서행 -> 원활) 및 저속 순으로 정렬해 반환한다.
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
