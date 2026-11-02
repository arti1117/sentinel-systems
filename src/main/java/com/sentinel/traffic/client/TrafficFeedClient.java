package com.sentinel.traffic.client;

import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficSource;
import java.util.List;

/**
 * 외부 교통 공공데이터 제공처별 소통 데이터 조회 및 수동 실시간 갱신 인터페이스.
 * 쿼터 보호를 위해 기동 시에는 로컬 캐시만 읽고, 수동 새로고침 요청 시에만 외부 API를 호출한다.
 */
public interface TrafficFeedClient {

    record LiveRefreshResult(List<RoadLinkTraffic> updatedLinks, int apiCallsUsed) {
    }

    TrafficSource source();

    List<RoadLinkTraffic> fetchCachedTraffic();

    LiveRefreshResult refreshLiveTraffic(String targetLinkId);
}
