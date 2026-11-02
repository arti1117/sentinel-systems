package com.sentinel.traffic.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.traffic.client.RoadNetworkCatalog.CachedMeasurement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 도로 소통 실측 스냅샷 저장소.
 * 비정상 센서 속도(0 이하, 250km/h 초과, NaN/Infinity), 과거 관측 시각(역전 타임스탬프),
 * 손상된 JSON 엔트리를 방어적으로 차단한다.
 */
@Component
public class RedisTrafficSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTrafficSnapshotStore.class);
    public static final String REDIS_SNAPSHOT_KEY = "sentinel:traffic:live:snapshots";
    private static final double MAX_VALID_SPEED_KPH = 250.0;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisTrafficSnapshotStore(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isAvailable() {
        return redisTemplate != null;
    }

    /**
     * Redis 스냅샷 해시가 비어 있는 경우 초기 시드 캐시를 적재한다.
     */
    public void seedFromCatalogIfEmpty(Map<String, CachedMeasurement> initialMeasurements) {
        if (redisTemplate == null || initialMeasurements == null || initialMeasurements.isEmpty()) {
            return;
        }
        try {
            Long size = redisTemplate.opsForHash().size(REDIS_SNAPSHOT_KEY);
            if (size != null && size > 0) {
                return;
            }
            initialMeasurements.forEach((linkId, m) -> {
                if (m != null) {
                    saveIfNewer(linkId, m.speedKph(), m.observedAt());
                }
            });
        } catch (DataAccessException e) {
            log.warn("Redis 초기 스냅샷 시드 적재 실패 (로컬 캐시 폴백 유지): {}", e.getMessage());
        }
    }

    /**
     * 유효한 속도 및 최신 관측 시각인 경우에만 Redis에 저장한다.
     * 과거 타임스탬프이거나 비정상 속도이면 false를 반환하고 기존 값을 보존한다.
     */
    public boolean saveIfNewer(String linkId, double speedKph, Instant observedAt) {
        if (linkId == null || linkId.isBlank() || observedAt == null || !isValidSpeed(speedKph)) {
            return false;
        }
        if (redisTemplate == null) {
            return false;
        }
        double roundedSpeed = Math.round(speedKph * 10.0) / 10.0;
        try {
            Optional<CachedMeasurement> existing = findByLinkId(linkId);
            if (existing.isPresent() && observedAt.isBefore(existing.get().observedAt())) {
                return false;
            }
            String payload = objectMapper.writeValueAsString(Map.of(
                    "speedKph", roundedSpeed,
                    "observedAt", observedAt.toString()
            ));
            redisTemplate.opsForHash().put(REDIS_SNAPSHOT_KEY, linkId, payload);
            return true;
        } catch (Exception e) {
            log.warn("Redis 스냅샷 저장 실패 (linkId={}): {}", linkId, e.getMessage());
            return false;
        }
    }

    /**
     * 특정 도로 구간의 실측 스냅샷을 조회한다. 손상된 엔트리는 무시하고 Optional.empty()를 반환한다.
     */
    public Optional<CachedMeasurement> findByLinkId(String linkId) {
        if (redisTemplate == null || linkId == null || linkId.isBlank()) {
            return Optional.empty();
        }
        try {
            Object raw = redisTemplate.opsForHash().get(REDIS_SNAPSHOT_KEY, linkId);
            if (raw == null) {
                return Optional.empty();
            }
            return parseMeasurement(raw.toString());
        } catch (DataAccessException e) {
            log.warn("Redis 단건 조회 실패 (linkId={}): {}", linkId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Redis 해시 내 모든 정상 실측 스냅샷을 복원한다. 손상된 JSON이나 비정상 값은 건너뛴다.
     */
    public Map<String, CachedMeasurement> findAllValid() {
        Map<String, CachedMeasurement> result = new LinkedHashMap<>();
        if (redisTemplate == null) {
            return result;
        }
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(REDIS_SNAPSHOT_KEY);
            entries.forEach((k, v) -> {
                if (k != null && v != null) {
                    parseMeasurement(v.toString()).ifPresent(m -> result.put(k.toString(), m));
                }
            });
        } catch (DataAccessException e) {
            log.warn("Redis 전체 스냅샷 조회 실패: {}", e.getMessage());
        }
        return result;
    }

    private Optional<CachedMeasurement> parseMeasurement(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return Optional.empty();
            }
            JsonNode speedNode = node.get("speedKph");
            JsonNode observedNode = node.get("observedAt");
            if (speedNode == null || !speedNode.isNumber() || observedNode == null || !observedNode.isTextual()) {
                return Optional.empty();
            }
            double speed = speedNode.asDouble();
            if (!isValidSpeed(speed)) {
                return Optional.empty();
            }
            Instant observedAt = Instant.parse(observedNode.asText());
            return Optional.of(new CachedMeasurement(Math.round(speed * 10.0) / 10.0, observedAt));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static boolean isValidSpeed(double speedKph) {
        return !Double.isNaN(speedKph)
                && !Double.isInfinite(speedKph)
                && speedKph > 0.0
                && speedKph <= MAX_VALID_SPEED_KPH;
    }
}
