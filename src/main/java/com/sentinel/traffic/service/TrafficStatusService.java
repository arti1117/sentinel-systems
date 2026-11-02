package com.sentinel.traffic.service;

import com.sentinel.traffic.client.RoadNetworkCatalog;
import com.sentinel.traffic.client.TrafficFeedClient;
import com.sentinel.traffic.client.TrafficFeedClient.LiveRefreshResult;
import com.sentinel.traffic.domain.CongestionLevel;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficOverviewResponse;
import com.sentinel.traffic.domain.TrafficSource;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.locationtech.jts.geom.Envelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 1단계(도로현황) 서비스: 국토부 ITS와 서울시 TOPIS 도로 구간을 통합 관리한다.
 * 월간 오픈API 쿼터(100건) 보호를 위해 서버 기동 및 일반 조회 시에는 로컬 캐시만 사용하며,
 * 사용자의 수동 새로고침 요청 시에만 외부 오픈API를 호출한다.
 */
@Service
public class TrafficStatusService {

    private final List<TrafficFeedClient> feedClients;
    private final RoadNetworkCatalog catalog;
    private final Map<LinkKey, RoadLinkTraffic> latestByLink = new ConcurrentHashMap<>();
    private final AtomicInteger sessionApiCallsUsed = new AtomicInteger(0);
    private final AtomicReference<Instant> lastLiveRefreshAt = new AtomicReference<>(null);

    public record LinkKey(TrafficSource source, String linkId) {
    }

    public record ManualRefreshSummary(
            int updatedCount,
            int apiCallsUsedNow,
            int sessionApiCallsUsed,
            Instant lastLiveRefreshAt
    ) {
    }

    public TrafficStatusService(List<TrafficFeedClient> feedClients) {
        this(feedClients, new RoadNetworkCatalog(false));
    }

    @Autowired
    public TrafficStatusService(List<TrafficFeedClient> feedClients, RoadNetworkCatalog catalog) {
        this.feedClients = feedClients;
        this.catalog = catalog;
    }

    @PostConstruct
    public void init() {
        loadFromCacheOnly();
    }

    /**
     * 외부 API 호출(쿼터 차감) 없이 로컬 캐시와 기본 스냅샷만 메모리에 적재한다.
     */
    public int loadFromCacheOnly() {
        int loaded = 0;
        for (TrafficFeedClient client : feedClients) {
            for (RoadLinkTraffic item : client.fetchCachedTraffic()) {
                if (upsertLinkTraffic(item)) {
                    loaded++;
                }
            }
        }
        Instant cachedTimestamp = catalog.latestCachedObservedAt();
        if (cachedTimestamp != null && lastLiveRefreshAt.get() == null) {
            lastLiveRefreshAt.set(cachedTimestamp);
        }
        return loaded;
    }

    /**
     * 사용자가 수동 새로고침 버튼을 눌렀을 때만 외부 오픈API를 호출해 실시간 속도를 갱신한다.
     */
    public ManualRefreshSummary refreshLiveFeeds(TrafficSource targetSource, String targetLinkId) {
        int updated = 0;
        int callsNow = 0;

        for (TrafficFeedClient client : feedClients) {
            if (targetSource != null && client.source() != targetSource) {
                continue;
            }
            LiveRefreshResult result = client.refreshLiveTraffic(targetLinkId);
            callsNow += result.apiCallsUsed();
            for (RoadLinkTraffic item : result.updatedLinks()) {
                if (upsertLinkTraffic(item)) {
                    updated++;
                }
            }
        }

        int totalCalls = sessionApiCallsUsed.addAndGet(callsNow);
        Instant refreshedAt = Instant.now();
        lastLiveRefreshAt.set(refreshedAt);
        return new ManualRefreshSummary(updated, callsNow, totalCalls, refreshedAt);
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
        return getOverview(sourceFilter, congestionFilter, null, null);
    }

    /**
     * 출처, 소통 등급, 지도 뷰포트 영역(bbox: minLng,minLat,maxLng,maxLat) 및 줌 레벨(zoom LOD)을 적용해 반환한다.
     */
    public TrafficOverviewResponse getOverview(
            TrafficSource sourceFilter,
            CongestionLevel congestionFilter,
            String bbox,
            Integer zoom
    ) {
        Set<String> viewportLinkIds = parseEnvelopeAndQuery(bbox);

        List<RoadLinkTraffic> filtered = latestByLink.values().stream()
                .filter(item -> sourceFilter == null || item.source() == sourceFilter)
                .filter(item -> congestionFilter == null || item.congestionLevel() == congestionFilter)
                .filter(item -> viewportLinkIds == null || viewportLinkIds.contains(item.linkId()))
                .map(item -> catalog.applyZoomLod(item, zoom))
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
                sessionApiCallsUsed.get(),
                lastLiveRefreshAt.get(),
                filtered
        );
    }

    private Set<String> parseEnvelopeAndQuery(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            double minLng = Double.parseDouble(parts[0].trim());
            double minLat = Double.parseDouble(parts[1].trim());
            double maxLng = Double.parseDouble(parts[2].trim());
            double maxLat = Double.parseDouble(parts[3].trim());
            if (!Double.isFinite(minLng) || !Double.isFinite(minLat)
                    || !Double.isFinite(maxLng) || !Double.isFinite(maxLat)) {
                return null;
            }
            Envelope envelope = new Envelope(minLng, maxLng, minLat, maxLat);
            if (envelope.getMinX() < -180.0 || envelope.getMaxX() > 180.0
                    || envelope.getMinY() < -90.0 || envelope.getMaxY() > 90.0) {
                return Set.of();
            }
            return catalog.queryLinkIdsByEnvelope(envelope);
        } catch (NumberFormatException e) {
            return null;
        }
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
