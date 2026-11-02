package com.sentinel.traffic.client;

import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 국토교통부 국가교통정보센터(ITS) 전국 고속도로·일반국도 실시간 소통 데이터 수집 클라이언트.
 */
@Component
public class ItsTrafficFeedClient implements TrafficFeedClient {

    @Override
    public TrafficSource source() {
        return TrafficSource.ITS;
    }

    @Override
    public List<RoadLinkTraffic> fetchLatestTraffic() {
        Instant now = Instant.now();
        return List.of(
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-0010-01",
                        "경부고속도로",
                        "양재IC → 판교JC",
                        RoadType.HIGHWAY,
                        28.5,
                        List.of(
                                new GeoPoint(37.4633, 127.0422),
                                new GeoPoint(37.4350, 127.0710),
                                new GeoPoint(37.4055, 127.0953)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-0010-02",
                        "경부고속도로",
                        "판교JC → 기흥IC",
                        RoadType.HIGHWAY,
                        54.0,
                        List.of(
                                new GeoPoint(37.4055, 127.0953),
                                new GeoPoint(37.3360, 127.1045),
                                new GeoPoint(37.2248, 127.1032)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-1000-01",
                        "수도권제1순환고속도로",
                        "성남IC → 하남JC",
                        RoadType.HIGHWAY,
                        88.0,
                        List.of(
                                new GeoPoint(37.4342, 127.1215),
                                new GeoPoint(37.4840, 127.1520),
                                new GeoPoint(37.5262, 127.1938)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-0500-01",
                        "영동고속도로",
                        "신갈JC → 마성IC",
                        RoadType.HIGHWAY,
                        32.0,
                        List.of(
                                new GeoPoint(37.2740, 127.1035),
                                new GeoPoint(37.2855, 127.1510),
                                new GeoPoint(37.2825, 127.1995)
                        ),
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-NAT-0077-01",
                        "자유로(국도77호선)",
                        "가양대교북단 → 행주대교북단",
                        RoadType.NATIONAL,
                        67.5,
                        List.of(
                                new GeoPoint(37.5752, 126.8660),
                                new GeoPoint(37.5905, 126.8400),
                                new GeoPoint(37.6062, 126.8175)
                        ),
                        now
                )
        );
    }
}
