package com.sentinel.traffic;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.traffic.client.ItsTrafficFeedClient;
import com.sentinel.traffic.client.TopisTrafficFeedClient;
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
        assertThat(all.links().getFirst().congestionLevel()).isEqualTo(CongestionLevel.CONGESTED);
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
}
