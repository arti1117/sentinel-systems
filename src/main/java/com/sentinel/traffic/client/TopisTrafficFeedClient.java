package com.sentinel.traffic.client;

import com.sentinel.traffic.client.RoadNetworkCatalog.RoadSectionSeed;
import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 서울 열린데이터광장(TOPIS) 서울시 도시고속도로·주요 간선도로(12개 노선) 수집 클라이언트.
 * 기동 시에는 쿼터를 소모하지 않고 캐시를 로드하며, 수동 새로고침 시에만 오픈API를 호출한다.
 */
@Component
public class TopisTrafficFeedClient implements TrafficFeedClient {

    private static final Logger log = LoggerFactory.getLogger(TopisTrafficFeedClient.class);
    private static final Pattern PRCS_SPD_PATTERN = Pattern.compile("<prcs_spd>([0-9.]+)</prcs_spd>");

    private final PublicApiProperties properties;
    private final RoadNetworkCatalog catalog;
    private final RestClient restClient;

    public TopisTrafficFeedClient() {
        this(new PublicApiProperties(3000L, "", ""), new RoadNetworkCatalog());
    }

    @Autowired
    public TopisTrafficFeedClient(PublicApiProperties properties, RoadNetworkCatalog catalog) {
        this.properties = properties;
        this.catalog = catalog;
        this.restClient = RestClient.create();
    }

    @Override
    public TrafficSource source() {
        return TrafficSource.TOPIS;
    }

    @Override
    public List<RoadLinkTraffic> fetchCachedTraffic() {
        Instant now = Instant.now();
        return catalog.sectionsBySource(TrafficSource.TOPIS).stream()
                .map(seed -> catalog.toRoadLinkTraffic(seed, now))
                .toList();
    }

    @Override
    public LiveRefreshResult refreshLiveTraffic(String targetLinkId) {
        Instant now = Instant.now();
        List<RoadSectionSeed> seeds = catalog.sectionsBySource(TrafficSource.TOPIS);
        List<RoadLinkTraffic> updated = new ArrayList<>();
        int apiCalls = 0;

        for (RoadSectionSeed seed : seeds) {
            if (targetLinkId != null && !targetLinkId.isBlank() && !seed.linkId().equals(targetLinkId)) {
                continue;
            }
            if (properties.hasTopisKey()) {
                apiCalls++;
                Double liveSpeed = fetchLiveSpeedFromSeoulApi(seed.apiLinkId());
                if (liveSpeed != null && liveSpeed > 0.0) {
                    catalog.updateCachedSpeed(seed.linkId(), liveSpeed);
                }
            }
            updated.add(catalog.toRoadLinkTraffic(seed, now));
        }

        if (apiCalls > 0) {
            catalog.persistDiskSpeedCache();
        }
        return new LiveRefreshResult(updated, apiCalls);
    }

    private Double fetchLiveSpeedFromSeoulApi(String topisApiLinkId) {
        String url = "http://openapi.seoul.go.kr:8088/{key}/xml/TrafficInfo/1/1/{linkId}";
        try {
            String xml = restClient.get()
                    .uri(url, properties.topisApiKey().trim(), topisApiLinkId)
                    .retrieve()
                    .body(String.class);
            if (xml != null) {
                Matcher matcher = PRCS_SPD_PATTERN.matcher(xml);
                if (matcher.find()) {
                    return Double.parseDouble(matcher.group(1));
                }
            }
        } catch (Exception e) {
            log.warn("TOPIS TrafficInfo API 호출 실패 (linkId={}): {}", topisApiLinkId, e.getMessage());
        }
        return null;
    }
}
