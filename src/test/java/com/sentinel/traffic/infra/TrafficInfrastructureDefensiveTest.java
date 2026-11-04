package com.sentinel.traffic.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sentinel.traffic.client.RoadNetworkCatalog;
import com.sentinel.traffic.client.RoadNetworkCatalog.CachedMeasurement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TrafficInfrastructureDefensiveTest {

    @Autowired
    private PostgisRoadSpatialRepository postgisRepository;

    @Autowired
    private RedisTrafficSnapshotStore redisSnapshotStore;

    @Autowired
    private RoadNetworkCatalog catalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(RedisTrafficSnapshotStore.REDIS_SNAPSHOT_KEY);
        redisSnapshotStore.seedFromCatalogIfEmpty(catalog.getAllCachedMeasurements());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.opsForHash().delete(
                RedisTrafficSnapshotStore.REDIS_SNAPSHOT_KEY,
                "CORRUPTED-LINK-01",
                "CORRUPTED-LINK-02"
        );
    }

    @Test
    @DisplayName("PostGIS: 도로망 21개 구간과 GIST 공간 인덱스가 정상 적재되고 ST_Intersects 공간 검색이 동작한다")
    void postgisSeedsRoadSectionsAndQueriesByBoundingBox() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM road_sections", Integer.class);
        assertThat(count).isEqualTo(21);

        Integer gistIndexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'road_sections' AND indexdef ILIKE '%USING gist%'",
                Integer.class
        );
        assertThat(gistIndexCount).isGreaterThanOrEqualTo(1);

        // 서울 도심(강변북로·올림픽대로·내부순환로 통과 영역) BBox 조회
        Set<String> matchedLinks = postgisRepository.findLinkIdsIntersecting(126.90, 37.50, 127.05, 37.60);
        assertThat(matchedLinks)
                .isNotEmpty()
                .contains("TOPIS-UHWY-070-01", "TOPIS-UHWY-088-01");
    }

    @Test
    @DisplayName("PostGIS 방어 검증: 역전된 BBox 좌표, NaN/Infinity, WGS84 범위 초과 입력을 안전하게 차단·보정한다")
    void postgisRejectsOrNormalizesInvalidBoundingBoxInputs() {
        // 1. NaN / Infinity 입력 시 예외 전파 없이 빈 결과 반환
        assertThat(postgisRepository.findLinkIdsIntersecting(Double.NaN, 37.5, 127.0, 37.6)).isEmpty();
        assertThat(postgisRepository.findLinkIdsIntersecting(126.9, 37.5, Double.POSITIVE_INFINITY, 37.6)).isEmpty();

        // 2. WGS84 경위도 범위(-180~180, -90~90) 초과 시 빈 결과 반환
        assertThat(postgisRepository.findLinkIdsIntersecting(-200.0, 37.5, 127.0, 37.6)).isEmpty();
        assertThat(postgisRepository.findLinkIdsIntersecting(126.9, -95.0, 127.0, 95.0)).isEmpty();

        // 3. min/max가 뒤집힌 역전 BBox(minLng > maxLng, minLat > maxLat) 입력 시 정규화하여 정상 검색 수행
        Set<String> invertedResult = postgisRepository.findLinkIdsIntersecting(127.05, 37.60, 126.90, 37.50);
        Set<String> normalResult = postgisRepository.findLinkIdsIntersecting(126.90, 37.50, 127.05, 37.60);
        assertThat(invertedResult).isEqualTo(normalResult).isNotEmpty();
    }

    @Test
    @DisplayName("Redis 방어 검증: 음수·0·비정상 초고속(>250km/h)·NaN 및 과거 시각(역전 타임스탬프) 데이터를 거부한다")
    void redisStoreBlocksInvalidSpeedsAndOutOfOrderTimestamps() {
        String linkId = "TOPIS-UHWY-070-01";
        Instant baseTime = Instant.now().plusSeconds(300);

        boolean savedValid = redisSnapshotStore.saveIfNewer(linkId, 58.4, baseTime);
        assertThat(savedValid).isTrue();

        // 과거 시각 데이터(역전 타임스탬프)는 거부되어야 함
        boolean savedStale = redisSnapshotStore.saveIfNewer(linkId, 12.3, baseTime.minusSeconds(60));
        assertThat(savedStale).isFalse();
        assertThat(redisSnapshotStore.findByLinkId(linkId))
                .isPresent()
                .get()
                .satisfies(m -> {
                    assertThat(m.speedKph()).isEqualTo(58.4);
                    assertThat(m.observedAt()).isEqualTo(baseTime);
                });

        // 비정상 속도(0, 음수, NaN, Infinity, 250km/h 초과)는 미래 시각이더라도 거부되어야 함
        Instant futureTime = baseTime.plusSeconds(120);
        assertThat(redisSnapshotStore.saveIfNewer(linkId, 0.0, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer(linkId, -10.5, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer(linkId, Double.NaN, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer(linkId, Double.POSITIVE_INFINITY, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer(linkId, 280.0, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer("", 60.0, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer(null, 60.0, futureTime)).isFalse();
        assertThat(redisSnapshotStore.saveIfNewer(linkId, 60.0, null)).isFalse();

        // 기존 정상 값은 그대로 보존되어야 함
        assertThat(redisSnapshotStore.findByLinkId(linkId))
                .isPresent()
                .get()
                .extracting(CachedMeasurement::speedKph)
                .isEqualTo(58.4);
    }

    @Test
    @DisplayName("Redis 방어 검증: 해시 내부에 손상된 JSON이 섞여 있어도 예외 없이 정상 항목만 복원한다")
    void redisStoreGracefullySkipsCorruptedJsonEntries() {
        redisTemplate.opsForHash().put(
                RedisTrafficSnapshotStore.REDIS_SNAPSHOT_KEY,
                "CORRUPTED-LINK-01",
                "{broken-json"
        );
        redisTemplate.opsForHash().put(
                RedisTrafficSnapshotStore.REDIS_SNAPSHOT_KEY,
                "CORRUPTED-LINK-02",
                "{\"speedKph\": -99.0, \"observedAt\": \"not-a-timestamp\"}"
        );

        assertThatCode(() -> {
            Map<String, CachedMeasurement> loaded = redisSnapshotStore.findAllValid();
            assertThat(loaded)
                    .doesNotContainKeys("CORRUPTED-LINK-01", "CORRUPTED-LINK-02")
                    .containsKey("TOPIS-UHWY-070-01");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("RoadNetworkCatalog 통합: PostGIS 공간 인덱스와 Redis 실측 스냅샷이 연동되어 동작한다")
    void catalogIntegratesPostgisAndRedisWithDefensiveFallback() {
        Envelope seoulEnvelope = new Envelope(126.90, 127.05, 37.50, 37.60);
        Set<String> linkIds = catalog.queryLinkIdsByEnvelope(seoulEnvelope);
        assertThat(linkIds).contains("TOPIS-UHWY-070-01", "TOPIS-UHWY-088-01");

        Instant newObservedAt = Instant.now().plusSeconds(60);
        catalog.updateCachedSpeed("TOPIS-UHWY-070-01", 64.2, newObservedAt);

        assertThat(redisSnapshotStore.findByLinkId("TOPIS-UHWY-070-01"))
                .isPresent()
                .get()
                .extracting(CachedMeasurement::speedKph)
                .isEqualTo(64.2);
    }

    @Test
    @DisplayName("Redis 동시성 방어 검증: 다중 스레드가 역전된 시각 데이터를 동시에 갱신해도 최신 시각 데이터가 절대 덮어써지지 않는다")
    void redisStorePreventsLostUpdateUnderConcurrentOutOfOrderWrites() throws Exception {
        int rounds = 40;
        int threadsPerRound = 8;
        Instant baseTime = Instant.parse("2026-11-04T05:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerRound);

        try {
            for (int round = 0; round < rounds; round++) {
                String linkId = "CONCURRENT-LINK-" + round;
                CyclicBarrier startBarrier = new CyclicBarrier(threadsPerRound);
                List<Callable<Boolean>> tasks = new ArrayList<>(threadsPerRound);

                // 최신 시각(t + 80초, 88.0km/h)부터 과거 시각(t + 10초, 18.0km/h)까지 역순·동시 투입
                for (int i = threadsPerRound; i >= 1; i--) {
                    final double speed = 8.0 + (i * 10.0);
                    final Instant ts = baseTime.plusSeconds(i * 10L);
                    tasks.add(() -> {
                        startBarrier.await();
                        return redisSnapshotStore.saveIfNewer(linkId, speed, ts);
                    });
                }

                List<Future<Boolean>> futures = new ArrayList<>(threadsPerRound);
                for (Callable<Boolean> task : tasks) {
                    futures.add(pool.submit(task));
                }
                for (Future<Boolean> f : futures) {
                    f.get();
                }

                Instant expectedLatestTime = baseTime.plusSeconds(threadsPerRound * 10L);
                double expectedLatestSpeed = 8.0 + (threadsPerRound * 10.0);

                try {
                    assertThat(redisSnapshotStore.findByLinkId(linkId))
                            .as("round=%d 에서 최신 시각(%s) 데이터가 과거 시각 스레드에 덮어써지면 안 됨", round, expectedLatestTime)
                            .isPresent()
                            .get()
                            .satisfies(m -> {
                                assertThat(m.observedAt()).isEqualTo(expectedLatestTime);
                                assertThat(m.speedKph()).isEqualTo(expectedLatestSpeed);
                            });
                } finally {
                    redisTemplate.opsForHash().delete(RedisTrafficSnapshotStore.REDIS_SNAPSHOT_KEY, linkId);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
