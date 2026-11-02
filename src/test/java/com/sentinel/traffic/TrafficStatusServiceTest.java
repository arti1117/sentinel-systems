package com.sentinel.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.traffic.client.ItsTrafficFeedClient;
import com.sentinel.traffic.client.RoadNetworkCatalog;
import com.sentinel.traffic.client.TopisTrafficFeedClient;
import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.domain.CongestionLevel;
import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficOverviewResponse;
import com.sentinel.traffic.domain.TrafficSource;
import com.sentinel.traffic.service.TrafficStatusService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficStatusServiceTest {

    @Test
    @DisplayName("도로 종별(고속도로 vs 도심간선도로) 속도 기준에 따라 원활·서행·정체·결측 등급을 정확히 분류한다")
    void classifiesCongestionByRoadTypeAndGuardsInvalidSensorSpeed() {
        assertThat(RoadType.HIGHWAY.classify(30.0)).isEqualTo(CongestionLevel.CONGESTED);
        assertThat(RoadType.URBAN_ARTERIAL.classify(30.0)).isEqualTo(CongestionLevel.SMOOTH);
        assertThat(RoadType.URBAN_HIGHWAY.classify(35.0)).isEqualTo(CongestionLevel.SLOW);

        assertThat(RoadType.HIGHWAY.classify(-1.0)).isEqualTo(CongestionLevel.UNKNOWN);
        assertThat(RoadType.HIGHWAY.classify(0.0)).isEqualTo(CongestionLevel.UNKNOWN);
    }

    @Test
    @DisplayName("기동 시 외부 오픈API 쿼터를 소모하지 않고 수도권 21개 노선의 정밀 도로 곡선 좌표를 로드한다")
    void loads21RoadSectionsWithExactCurveGeometryWithoutApiCalls() {
        TrafficStatusService service = new TrafficStatusService(
                List.of(new ItsTrafficFeedClient(), new TopisTrafficFeedClient())
        );
        service.init();

        TrafficOverviewResponse all = service.getOverview(null, null);
        assertThat(all.totalLinks()).isEqualTo(21);
        assertThat(all.sessionApiCallsUsed()).isZero();
        assertThat(all.unknownCount()).isEqualTo(21);
        assertThat(all.links()).allMatch(link -> link.geometry().size() >= 50);

        TrafficOverviewResponse topisOnly = service.getOverview(TrafficSource.TOPIS, null);
        assertThat(topisOnly.totalLinks()).isEqualTo(12);
        assertThat(topisOnly.links()).allMatch(link -> link.source() == TrafficSource.TOPIS);
    }

    @Test
    @DisplayName("동일 구간에 대해 과거 관측 시각 패킷이 뒤늦게 도착하면 최신 도로 소통 상태를 덮어쓰지 않는다")
    void ignoresOutOfOrderTrafficObservation() {
        TrafficStatusService service = new TrafficStatusService(List.of());
        Instant newer = Instant.parse("2026-11-02T07:10:00Z");
        Instant older = newer.minus(Duration.ofMinutes(5));
        List<GeoPoint> path = List.of(
                new GeoPoint(37.4633, 127.0422),
                new GeoPoint(37.4055, 127.0953)
        );

        RoadLinkTraffic latest = RoadLinkTraffic.of(
                TrafficSource.ITS,
                "ITS-HWY-0010-01",
                "경부고속도로",
                "한남IC → 서초IC → 양재IC",
                RoadType.HIGHWAY,
                25.0,
                path,
                newer
        );
        RoadLinkTraffic delayedOld = RoadLinkTraffic.of(
                TrafficSource.ITS,
                "ITS-HWY-0010-01",
                "경부고속도로",
                "한남IC → 서초IC → 양재IC",
                RoadType.HIGHWAY,
                95.0,
                path,
                older
        );

        assertThat(service.upsertLinkTraffic(latest)).isTrue();
        assertThat(service.upsertLinkTraffic(delayedOld)).isFalse();

        TrafficOverviewResponse overview = service.getOverview(TrafficSource.ITS, null);
        assertThat(overview.links()).hasSize(1);
        assertThat(overview.links().getFirst().speedKph()).isEqualTo(25.0);
        assertThat(overview.links().getFirst().congestionLevel()).isEqualTo(CongestionLevel.CONGESTED);
    }

    @Test
    @DisplayName("최근 오픈API 실측 스냅샷 캐시를 로드하면 쿼터 소모(0건) 없이 21개 전 구간의 실측 속도와 관측 시각이 표시된다")
    void loadsLatestSnapshotCacheForAll21SectionsWithZeroQuotaUsed() {
        RoadNetworkCatalog catalog = new RoadNetworkCatalog(true);
        PublicApiProperties emptyProps = new PublicApiProperties(3000L, "", "");
        TrafficStatusService service = new TrafficStatusService(
                List.of(
                        new ItsTrafficFeedClient(emptyProps, catalog),
                        new TopisTrafficFeedClient(emptyProps, catalog)
                ),
                catalog
        );
        service.init();

        TrafficOverviewResponse overview = service.getOverview(null, null);
        assertThat(overview.totalLinks()).isEqualTo(21);
        assertThat(overview.unknownCount()).isZero();
        assertThat(overview.sessionApiCallsUsed()).isZero();
        assertThat(overview.lastLiveRefreshAt()).isNotNull();
        assertThat(overview.averageSpeedKph()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("JTS STRtree 공간 인덱스(bbox)와 Douglas-Peucker 줌 레벨(LOD) 곡선 단순화가 정상 동작한다")
    void filtersByViewportBoundingBoxAndSimplifiesGeometryByZoomLevel() {
        RoadNetworkCatalog catalog = new RoadNetworkCatalog(true);
        PublicApiProperties emptyProps = new PublicApiProperties(3000L, "", "");
        TrafficStatusService service = new TrafficStatusService(
                List.of(
                        new ItsTrafficFeedClient(emptyProps, catalog),
                        new TopisTrafficFeedClient(emptyProps, catalog)
                ),
                catalog
        );
        service.init();

        // 강남역~삼성역(테헤란로/강남대로/경부고속도로) 주변 좁은 BBox 검색
        TrafficOverviewResponse gangnamViewport = service.getOverview(
                null,
                null,
                "127.01,37.48,127.07,37.52",
                13
        );
        assertThat(gangnamViewport.totalLinks()).isBetween(2, 10);
        assertThat(gangnamViewport.links())
                .extracting(RoadLinkTraffic::linkId)
                .contains("TOPIS-ART-101-01")
                .doesNotContain("ITS-NAT-0077-01");

        // 줌 레벨 9(광역 축소) vs 줌 레벨 13(상세 확대) 정점(Vertex) 개수 비교
        TrafficOverviewResponse zoom9 = service.getOverview(null, null, null, 9);
        TrafficOverviewResponse zoom13 = service.getOverview(null, null, null, 13);
        int verticesAtZoom9 = zoom9.links().stream().mapToInt(l -> l.geometry().size()).sum();
        int verticesAtZoom13 = zoom13.links().stream().mapToInt(l -> l.geometry().size()).sum();
        assertThat(verticesAtZoom9).isLessThan(verticesAtZoom13 / 2);

        // 비정상 BBox(NaN, 범위 초과) 방어 검증
        TrafficOverviewResponse nanBbox = service.getOverview(null, null, "NaN,37.48,127.07,37.52", 12);
        assertThat(nanBbox.totalLinks()).isEqualTo(21);

        TrafficOverviewResponse outOfBoundsBbox = service.getOverview(null, null, "-200.0,37.48,127.07,37.52", 12);
        assertThat(outOfBoundsBbox.totalLinks()).isZero();
    }
}
