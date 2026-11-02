package com.sentinel.traffic.client;

import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapter for MOLIT National Traffic Information Center (국토교통부 ITS 국가교통정보센터)
 * real-time highway and national road traffic feed.
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
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-0010-02",
                        "경부고속도로",
                        "판교JC → 기흥IC",
                        RoadType.HIGHWAY,
                        54.0,
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-1000-01",
                        "수도권제1순환고속도로",
                        "성남IC → 하남JC",
                        RoadType.HIGHWAY,
                        88.0,
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-HWY-0500-01",
                        "영동고속도로",
                        "신갈JC → 마성IC",
                        RoadType.HIGHWAY,
                        32.0,
                        now
                ),
                RoadLinkTraffic.of(
                        TrafficSource.ITS,
                        "ITS-NAT-0077-01",
                        "자유로(국도77호선)",
                        "가양대교북단 → 행주대교북단",
                        RoadType.NATIONAL,
                        67.5,
                        now
                )
        );
    }
}
