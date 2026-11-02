package com.sentinel.traffic.infra;

import com.sentinel.traffic.client.RoadNetworkCatalog.RoadSectionSeed;
import com.sentinel.traffic.domain.GeoPoint;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostGIS 기반 도로망 공간 저장소.
 * 도로 중심선(LineString, SRID 4326)과 GIST 공간 인덱스를 관리하며,
 * 잘못된 BBox 입력(NaN, Infinity, 역전 좌표, WGS84 범위 초과)을 방어한다.
 */
@Repository
public class PostgisRoadSpatialRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgisRoadSpatialRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public PostgisRoadSpatialRepository(@Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isAvailable() {
        return jdbcTemplate != null;
    }

    /**
     * PostGIS 테이블(road_sections) 및 GIST 공간 인덱스를 생성하고 시드 데이터를 적재한다.
     */
    public void initializeSchemaAndSeed(List<RoadSectionSeed> seeds) {
        if (jdbcTemplate == null || seeds == null || seeds.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS road_sections (
                        link_id VARCHAR(64) PRIMARY KEY,
                        source VARCHAR(16) NOT NULL,
                        api_link_id VARCHAR(64) NOT NULL,
                        road_name VARCHAR(128) NOT NULL,
                        section_name VARCHAR(255) NOT NULL,
                        road_type VARCHAR(32) NOT NULL,
                        its_route_type VARCHAR(16),
                        its_route_no VARCHAR(32),
                        its_drc_type VARCHAR(16),
                        geom GEOMETRY(LineString, 4326) NOT NULL
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_road_sections_geom
                    ON road_sections USING GIST (geom)
                    """);

            String upsertSql = """
                    INSERT INTO road_sections (
                        link_id, source, api_link_id, road_name, section_name,
                        road_type, its_route_type, its_route_no, its_drc_type, geom
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 4326)
                    )
                    ON CONFLICT (link_id) DO UPDATE SET
                        source = EXCLUDED.source,
                        api_link_id = EXCLUDED.api_link_id,
                        road_name = EXCLUDED.road_name,
                        section_name = EXCLUDED.section_name,
                        road_type = EXCLUDED.road_type,
                        its_route_type = EXCLUDED.its_route_type,
                        its_route_no = EXCLUDED.its_route_no,
                        its_drc_type = EXCLUDED.its_drc_type,
                        geom = EXCLUDED.geom
                    """;

            for (RoadSectionSeed seed : seeds) {
                if (seed.geometry() == null || seed.geometry().size() < 2) {
                    continue;
                }
                String wkt = toLineStringWkt(seed.geometry());
                jdbcTemplate.update(
                        upsertSql,
                        seed.linkId(),
                        seed.source().name(),
                        seed.apiLinkId(),
                        seed.roadName(),
                        seed.sectionName(),
                        seed.roadType().name(),
                        seed.itsRouteType(),
                        seed.itsRouteNo(),
                        seed.itsDrcType(),
                        wkt
                );
            }
        } catch (DataAccessException e) {
            log.warn("PostGIS 도로망 초기화 실패 (인메모리 JTS STRtree 폴백 유지): {}", e.getMessage());
        }
    }

    /**
     * BBox 영역과 교차하는 도로 구간 ID 집합을 PostGIS GIST 인덱스로 조회한다.
     */
    public Set<String> findLinkIdsIntersecting(double minLng, double minLat, double maxLng, double maxLat) {
        return tryFindLinkIdsIntersecting(minLng, minLat, maxLng, maxLat).orElseGet(Set::of);
    }

    /**
     * PostGIS 공간 조회를 시도하고, DB 장애 시 Optional.empty()를 반환해 JTS 폴백을 유도한다.
     * 입력값 방어 검증 실패 시에는 빈 집합(Optional.of(Set.of()))을 즉시 반환한다.
     */
    public Optional<Set<String>> tryFindLinkIdsIntersecting(
            double minLng,
            double minLat,
            double maxLng,
            double maxLat
    ) {
        if (!isFinite(minLng) || !isFinite(minLat) || !isFinite(maxLng) || !isFinite(maxLat)) {
            return Optional.of(Set.of());
        }

        double normMinLng = Math.min(minLng, maxLng);
        double normMaxLng = Math.max(minLng, maxLng);
        double normMinLat = Math.min(minLat, maxLat);
        double normMaxLat = Math.max(minLat, maxLat);

        if (normMinLng < -180.0 || normMaxLng > 180.0 || normMinLat < -90.0 || normMaxLat > 90.0) {
            return Optional.of(Set.of());
        }

        if (jdbcTemplate == null) {
            return Optional.empty();
        }

        String sql = """
                SELECT link_id
                FROM road_sections
                WHERE geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  AND ST_Intersects(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                """;
        try {
            List<String> rows = jdbcTemplate.queryForList(
                    sql,
                    String.class,
                    normMinLng, normMinLat, normMaxLng, normMaxLat,
                    normMinLng, normMinLat, normMaxLng, normMaxLat
            );
            return Optional.of(new LinkedHashSet<>(rows));
        } catch (DataAccessException e) {
            log.warn("PostGIS BBox 조회 실패, 인메모리 JTS STRtree로 폴백합니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String toLineStringWkt(List<GeoPoint> points) {
        String coordPairs = points.stream()
                .map(pt -> pt.lng() + " " + pt.lat())
                .collect(Collectors.joining(", "));
        return "LINESTRING(" + coordPairs + ")";
    }
}
