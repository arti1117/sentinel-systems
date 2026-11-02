package com.sentinel.traffic.client;

import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficSource;
import java.util.List;

/**
 * 외부 교통 공공데이터 제공처별 실시간 도로 소통 수집 포트 인터페이스.
 */
public interface TrafficFeedClient {

    TrafficSource source();

    List<RoadLinkTraffic> fetchLatestTraffic();
}
