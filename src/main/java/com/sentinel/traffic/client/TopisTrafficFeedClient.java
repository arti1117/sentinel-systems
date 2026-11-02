package com.sentinel.traffic.client;

import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapter for Seoul Open Data Plaza TOPIS (서울시 실시간 도로 소통정보)
 * urban highway and arterial road feed.
 */
@Component
public class TopisTrafficFeedClient implements TrafficFeedClient {

    @Override
    public TrafficSource source() {
        return TrafficSource.TOPIS;
    }

    @Override
    public List<RoadLinkTraffic> fetchLatestTraffic() {
        Instant now = Instant.now();
        return List.of(
                RoadLinkTraffic.of(
                        TrafficSource.TOPIS,
                        "TOPIS-UHWY-088-01",
                        "올림픽대로",
                        "한남대교남단 → 성수대교남단",
                        RoadType.URBAN_HIGHWAY,
                        19.5,
                        List.of(
                                new GeoPoint(37.5192, 127.0128),
                                new GeoPoint(37.5270, 127.0285),
                                new GeoPoint(37.5338, 127.0442)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.TOPIS,
                        "TOPIS-UHWY-070-01",
                        "강변북로",
                        "마포대교북단 → 한강대교북단",
                        RoadType.URBAN_HIGHWAY,
                        36.0,
                        List.of(
                                new GeoPoint(37.5365, 126.9412),
                                new GeoPoint(37.5280, 126.9535),
                                new GeoPoint(37.5218, 126.9665)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.TOPIS,
                        "TOPIS-UHWY-030-01",
                        "내부순환로",
                        "성산램프 → 홍은램프",
                        RoadType.URBAN_HIGHWAY,
                        58.0,
                        List.of(
                                new GeoPoint(37.5648, 126.9032),
                                new GeoPoint(37.5785, 126.9210),
                                new GeoPoint(37.5892, 126.9420)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.TOPIS,
                        "TOPIS-ART-101-01",
                        "테헤란로",
                        "삼성역사거리 → 역삼역사거리",
                        RoadType.URBAN_ARTERIAL,
                        12.0,
                        List.of(
                                new GeoPoint(37.5088, 127.0631),
                                new GeoPoint(37.5045, 127.0490),
                                new GeoPoint(37.5006, 127.0365)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.TOPIS,
                        "TOPIS-ART-102-01",
                        "강남대로",
                        "강남역사거리 → 신논현역교차로",
                        RoadType.URBAN_ARTERIAL,
                        18.5,
                        List.of(
                                new GeoPoint(37.4979, 127.0276),
                                new GeoPoint(37.5045, 127.0250)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.TOPIS,
                        "TOPIS-ART-103-01",
                        "세종대로",
                        "광화문삼거리 → 시청역교차로",
                        RoadType.URBAN_ARTERIAL,
                        29.0,
                        List.of(
                                new GeoPoint(37.5759, 126.9768),
                                new GeoPoint(37.5663, 126.9779)
                        ),
                        now
                )
        );
    }
}
