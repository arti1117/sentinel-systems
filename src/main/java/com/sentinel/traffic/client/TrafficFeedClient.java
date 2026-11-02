package com.sentinel.traffic.client;

import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficSource;
import java.util.List;

/**
 * Port interface for fetching real-time road traffic status from an external public data provider.
 */
public interface TrafficFeedClient {

    TrafficSource source();

    List<RoadLinkTraffic> fetchLatestTraffic();
}
