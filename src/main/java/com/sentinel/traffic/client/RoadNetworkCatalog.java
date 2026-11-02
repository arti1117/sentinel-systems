package com.sentinel.traffic.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficSource;
import com.sentinel.traffic.infra.PostgisRoadSpatialRepository;
import com.sentinel.traffic.infra.RedisTrafficSnapshotStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 수도권 주요 도로 구간의 정밀 중심선 좌표(road-network.json),
 * PostGIS GIST 공간 인덱스(폴백: JTS STRtree),
 * 그리고 Redis 실측 스냅샷 캐시(폴백: .sentinel-live-cache.json)를 통합 관리한다.
 */
@Component
public class RoadNetworkCatalog {

    private static final Logger log = LoggerFactory.getLogger(RoadNetworkCatalog.class);
    private static final Path LOCAL_CACHE_PATH = Path.of(".sentinel-live-cache.json");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();
    private final List<RoadSectionSeed> sections;
    private final STRtree spatialIndex = new STRtree();
    private final Map<String, LineString> lineStringByLinkId = new ConcurrentHashMap<>();
    private final Map<String, CachedMeasurement> cachedMeasurements = new ConcurrentHashMap<>();
    private final PostgisRoadSpatialRepository postgisRepository;
    private final RedisTrafficSnapshotStore redisSnapshotStore;

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

    public record CachedMeasurement(
            double speedKph,
            Instant observedAt
    ) {
    }

    public RoadNetworkCatalog() {
        this(true, null, null);
    }

    public RoadNetworkCatalog(boolean loadDiskCache) {
        this(loadDiskCache, null, null);
    }

    @Autowired
    public RoadNetworkCatalog(
            @Autowired(required = false) PostgisRoadSpatialRepository postgisRepository,
            @Autowired(required = false) RedisTrafficSnapshotStore redisSnapshotStore
    ) {
        this(true, postgisRepository, redisSnapshotStore);
    }

    private RoadNetworkCatalog(
            boolean loadDiskCache,
            PostgisRoadSpatialRepository postgisRepository,
            RedisTrafficSnapshotStore redisSnapshotStore
    ) {
        this.postgisRepository = postgisRepository;
        this.redisSnapshotStore = redisSnapshotStore;
        this.sections = loadSeedsFromClasspath();
        buildSpatialIndex();
        if (postgisRepository != null && postgisRepository.isAvailable()) {
            postgisRepository.initializeSchemaAndSeed(this.sections);
        }
        if (loadDiskCache) {
            loadClasspathSnapshotCache();
            loadDiskSpeedCache();
            syncWithRedisSnapshotStore();
        }
    }

    public List<RoadSectionSeed> sectionsBySource(TrafficSource source) {
        return sections.stream()
                .filter(s -> s.source() == source)
                .toList();
    }

    public Map<String, CachedMeasurement> getAllCachedMeasurements() {
        return Map.copyOf(cachedMeasurements);
    }

    /**
     * PostGIS GIST 공간 인덱스(ST_Intersects)를 우선 조회하고,
     * DB 미연결 또는 예외 발생 시 인메모리 JTS STRtree(R-Tree 공간 인덱스)로 자동 폴백한다.
     */
    public Set<String> queryLinkIdsByEnvelope(Envelope viewportEnvelope) {
        if (viewportEnvelope == null || viewportEnvelope.isNull()) {
            return Set.of();
        }
        if (postgisRepository != null && postgisRepository.isAvailable()) {
            Optional<Set<String>> postgisMatches = postgisRepository.tryFindLinkIdsIntersecting(
                    viewportEnvelope.getMinX(),
                    viewportEnvelope.getMinY(),
                    viewportEnvelope.getMaxX(),
                    viewportEnvelope.getMaxY()
            );
            if (postgisMatches.isPresent()) {
                return postgisMatches.get();
            }
        }
        return queryByEnvelope(viewportEnvelope).stream()
                .map(RoadSectionSeed::linkId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * JTS STRtree(R-Tree 공간 인덱스)를 사용해 지도 화면 영역(Envelope)과 교차하는 도로 링크만 추출한다.
     */
    @SuppressWarnings("unchecked")
    public List<RoadSectionSeed> queryByEnvelope(Envelope viewportEnvelope) {
        List<RoadSectionSeed> candidates = spatialIndex.query(viewportEnvelope);
        Geometry viewportGeom = geometryFactory.toGeometry(viewportEnvelope);
        return candidates.stream()
                .filter(seed -> {
                    LineString line = lineStringByLinkId.get(seed.linkId());
                    return line != null && line.intersects(viewportGeom);
                })
                .toList();
    }

    /**
     * 줌 레벨(LOD)에 따라 도로 구간의 곡선 좌표 정밀도를 동적으로 조절해 반환한다.
     */
    public RoadLinkTraffic applyZoomLod(RoadLinkTraffic item, Integer zoom) {
        if (zoom == null || zoom >= 12) {
            return item;
        }
        List<GeoPoint> simplified = simplifyGeometryForZoom(item.linkId(), item.geometry(), zoom);
        if (simplified == item.geometry()) {
            return item;
        }
        return new RoadLinkTraffic(
                item.source(),
                item.linkId(),
                item.roadName(),
                item.sectionName(),
                item.roadType(),
                item.speedKph(),
                item.congestionLevel(),
                simplified,
                item.observedAt()
        );
    }

    /**
     * 줌 레벨(LOD)에 따라 곡선 좌표 정밀도를 동적으로 조절(Douglas-Peucker Simplification)해 반환한다.
     */
    public List<GeoPoint> simplifyGeometryForZoom(String linkId, List<GeoPoint> original, Integer zoom) {
        if (zoom == null || zoom >= 12 || original.size() <= 20) {
            return original;
        }
        LineString line = lineStringByLinkId.get(linkId);
        if (line == null) {
            return original;
        }
        double tolerance = (zoom <= 9) ? 0.003 : (zoom == 10 ? 0.0012 : 0.0005);
        Geometry simplified = DouglasPeuckerSimplifier.simplify(line, tolerance);
        Coordinate[] coords = simplified.getCoordinates();
        if (coords.length < 2) {
            return original;
        }
        List<GeoPoint> result = new ArrayList<>(coords.length);
        for (Coordinate c : coords) {
            result.add(new GeoPoint(Math.round(c.y * 100000.0) / 100000.0, Math.round(c.x * 100000.0) / 100000.0));
        }
        return result;
    }

    public RoadLinkTraffic toRoadLinkTraffic(RoadSectionSeed seed, Instant fallbackObservedAt) {
        CachedMeasurement cached = cachedMeasurements.get(seed.linkId());
        double speed = (cached != null) ? cached.speedKph() : 0.0;
        Instant observedAt = (cached != null && cached.observedAt() != null)
                ? cached.observedAt()
                : fallbackObservedAt;
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
        updateCachedSpeed(linkId, speedKph, Instant.now());
    }

    public void updateCachedSpeed(String linkId, double speedKph, Instant observedAt) {
        if (linkId == null || linkId.isBlank() || observedAt == null || !RedisTrafficSnapshotStore.isValidSpeed(speedKph)) {
            return;
        }
        double rounded = Math.round(speedKph * 10.0) / 10.0;
        cachedMeasurements.compute(linkId, (k, existing) -> {
            if (existing != null && observedAt.isBefore(existing.observedAt())) {
                return existing;
            }
            return new CachedMeasurement(rounded, observedAt);
        });
        if (redisSnapshotStore != null && redisSnapshotStore.isAvailable()) {
            redisSnapshotStore.saveIfNewer(linkId, rounded, observedAt);
        }
    }

    public Instant latestCachedObservedAt() {
        return cachedMeasurements.values().stream()
                .map(CachedMeasurement::observedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    public void persistDiskSpeedCache() {
        try {
            Map<String, Map<String, Object>> serializable = new LinkedHashMap<>();
            cachedMeasurements.forEach((k, v) -> serializable.put(k, Map.of(
                    "speedKph", v.speedKph(),
                    "observedAt", v.observedAt().toString()
            )));
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(LOCAL_CACHE_PATH.toFile(), serializable);
        } catch (IOException e) {
            log.warn("로컬 속도 캐시 파일 저장 실패: {}", e.getMessage());
        }
    }

    private void syncWithRedisSnapshotStore() {
        if (redisSnapshotStore == null || !redisSnapshotStore.isAvailable()) {
            return;
        }
        redisSnapshotStore.seedFromCatalogIfEmpty(cachedMeasurements);
        Map<String, CachedMeasurement> fromRedis = redisSnapshotStore.findAllValid();
        fromRedis.forEach((linkId, redisMeasurement) -> {
            cachedMeasurements.compute(linkId, (k, local) -> {
                if (local == null || !redisMeasurement.observedAt().isBefore(local.observedAt())) {
                    return redisMeasurement;
                }
                return local;
            });
        });
    }

    private void buildSpatialIndex() {
        for (RoadSectionSeed seed : sections) {
            Coordinate[] coords = seed.geometry().stream()
                    .map(pt -> new Coordinate(pt.lng(), pt.lat()))
                    .toArray(Coordinate[]::new);
            if (coords.length >= 2) {
                LineString lineString = geometryFactory.createLineString(coords);
                lineStringByLinkId.put(seed.linkId(), lineString);
                spatialIndex.insert(lineString.getEnvelopeInternal(), seed);
            }
        }
        spatialIndex.build();
    }

    private List<RoadSectionSeed> loadSeedsFromClasspath() {
        try (InputStream is = new ClassPathResource("data/road-network.json").getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<RoadSectionSeed>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("data/road-network.json 로드 실패", e);
        }
    }

    private void loadClasspathSnapshotCache() {
        ClassPathResource snapshotResource = new ClassPathResource("data/initial-live-snapshot.json");
        if (!snapshotResource.exists()) {
            return;
        }
        try (InputStream is = snapshotResource.getInputStream()) {
            parseSnapshotNode(objectMapper.readTree(is));
        } catch (IOException e) {
            log.warn("기본 실측 스냅샷 파일 읽기 실패: {}", e.getMessage());
        }
    }

    private void loadDiskSpeedCache() {
        if (!Files.exists(LOCAL_CACHE_PATH)) {
            return;
        }
        try {
            parseSnapshotNode(objectMapper.readTree(LOCAL_CACHE_PATH.toFile()));
        } catch (IOException e) {
            log.warn("로컬 속도 캐시 파일 읽기 실패: {}", e.getMessage());
        }
    }

    private void parseSnapshotNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return;
        }
        root.fields().forEachRemaining(entry -> {
            String linkId = entry.getKey();
            JsonNode val = entry.getValue();
            if (val.isNumber()) {
                double spd = val.asDouble();
                if (RedisTrafficSnapshotStore.isValidSpeed(spd)) {
                    cachedMeasurements.put(
                            linkId,
                            new CachedMeasurement(spd, Instant.parse("2026-11-02T07:55:00Z"))
                    );
                }
            } else if (val.isObject()) {
                double spd = val.path("speedKph").asDouble(0.0);
                String obsStr = val.path("observedAt").asText("");
                Instant obs = obsStr.isBlank() ? Instant.now() : Instant.parse(obsStr);
                if (RedisTrafficSnapshotStore.isValidSpeed(spd)) {
                    cachedMeasurements.put(linkId, new CachedMeasurement(spd, obs));
                }
            }
        });
    }
}
