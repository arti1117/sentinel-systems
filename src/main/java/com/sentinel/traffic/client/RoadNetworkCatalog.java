package com.sentinel.traffic.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 수도권 21개 주요 도로 구간의 정밀 중심선 좌표(road-network.json)와
 * 로컬 디스크 실측 속도 캐시(.sentinel-live-cache.json)를 관리한다.
 * 오픈API에서 아직 수신되지 않은 구간은 가짜 기본값을 넣지 않고 0.0(UNKNOWN 미수신)으로 반환한다.
 */
@Component
public class RoadNetworkCatalog {

    private static final Logger log = LoggerFactory.getLogger(RoadNetworkCatalog.class);
    private static final Path LOCAL_CACHE_PATH = Path.of(".sentinel-live-cache.json");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RoadSectionSeed> sections;
    private final Map<String, Double> cachedSpeeds = new ConcurrentHashMap<>();

    public record RoadSectionSeed(
            TrafficSource source,
            String linkId,
            String apiLinkId,
            String roadName,
            String sectionName,
            RoadType roadType,
            String itsRouteType,
            String itsRouteNo,
            String itsDrcType,
            List<GeoPoint> geometry
    ) {
    }

    public RoadNetworkCatalog() {
        this(true);
    }

    public RoadNetworkCatalog(boolean loadDiskCache) {
        this.sections = loadSeedsFromClasspath();
        if (loadDiskCache) {
            loadDiskSpeedCache();
        }
    }

    public List<RoadSectionSeed> sectionsBySource(TrafficSource source) {
        return sections.stream()
                .filter(s -> s.source() == source)
                .toList();
    }

    public RoadLinkTraffic toRoadLinkTraffic(RoadSectionSeed seed, Instant observedAt) {
        double speed = cachedSpeeds.getOrDefault(seed.linkId(), 0.0);
        return RoadLinkTraffic.of(
                seed.source(),
                seed.linkId(),
                seed.roadName(),
                seed.sectionName(),
                seed.roadType(),
                speed,
                seed.geometry(),
                observedAt
        );
    }

    public void updateCachedSpeed(String linkId, double speedKph) {
        if (speedKph > 0.0) {
            cachedSpeeds.put(linkId, Math.round(speedKph * 10.0) / 10.0);
        }
    }

    public void persistDiskSpeedCache() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(LOCAL_CACHE_PATH.toFile(), cachedSpeeds);
        } catch (IOException e) {
            log.warn("로컬 속도 캐시 파일 저장 실패: {}", e.getMessage());
        }
    }

    private List<RoadSectionSeed> loadSeedsFromClasspath() {
        try (InputStream is = new ClassPathResource("data/road-network.json").getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<RoadSectionSeed>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("data/road-network.json 로드 실패", e);
        }
    }

    private void loadDiskSpeedCache() {
        if (!Files.exists(LOCAL_CACHE_PATH)) {
            return;
        }
        try {
            Map<String, Double> saved = objectMapper.readValue(
                    LOCAL_CACHE_PATH.toFile(),
                    new TypeReference<Map<String, Double>>() {}
            );
            cachedSpeeds.putAll(saved);
        } catch (IOException e) {
            log.warn("로컬 속도 캐시 파일 읽기 실패: {}", e.getMessage());
        }
    }
}
