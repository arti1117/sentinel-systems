package com.sentinel.traffic.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.traffic.client.RoadNetworkCatalog.RoadSectionSeed;
import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 국토교통부 국가교통정보센터(ITS) 고속도로·일반국도(9개 노선) 수집 클라이언트.
 * 기동 시에는 쿼터를 소모하지 않고 캐시를 로드하며, 수동 새로고침 요청 시에만 단 1건의 API를 호출한다.
 */
@Component
public class ItsTrafficFeedClient implements TrafficFeedClient {

    private static final Logger log = LoggerFactory.getLogger(ItsTrafficFeedClient.class);

    private final PublicApiProperties properties;
    private final RoadNetworkCatalog catalog;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ItsTrafficFeedClient() {
        this(new PublicApiProperties(3000L, "", ""), new RoadNetworkCatalog());
    }

    @Autowired
    public ItsTrafficFeedClient(PublicApiProperties properties, RoadNetworkCatalog catalog) {
        this.properties = properties;
        this.catalog = catalog;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public TrafficSource source() {
        return TrafficSource.ITS;
    }

    @Override
    public List<RoadLinkTraffic> fetchCachedTraffic() {
        Instant now = Instant.now();
        return catalog.sectionsBySource(TrafficSource.ITS).stream()
                .map(seed -> catalog.toRoadLinkTraffic(seed, now))
                .toList();
    }

    @Override
    public LiveRefreshResult refreshLiveTraffic(String targetLinkId) {
        List<RoadSectionSeed> seeds = catalog.sectionsBySource(TrafficSource.ITS);
        if (targetLinkId != null && !targetLinkId.isBlank()
                && seeds.stream().noneMatch(s -> s.linkId().equals(targetLinkId))) {
            return new LiveRefreshResult(List.of(), 0);
        }

        int apiCalls = 0;
        if (properties.hasItsKey()) {
            apiCalls = 1;
            fetchItsBatchAndUpdateCache(seeds);
            catalog.persistDiskSpeedCache();
        }

        Instant now = Instant.now();
        List<RoadLinkTraffic> updated = seeds.stream()
                .filter(s -> targetLinkId == null || targetLinkId.isBlank() || s.linkId().equals(targetLinkId))
                .map(s -> catalog.toRoadLinkTraffic(s, now))
                .toList();
        return new LiveRefreshResult(updated, apiCalls);
    }

    private void fetchItsBatchAndUpdateCache(List<RoadSectionSeed> seeds) {
        String url = "https://openapi.its.go.kr:9443/trafficInfo"
                + "?apiKey={key}&type=ex&routeNo=0010&drcType=up"
                + "&minX=126.75&maxX=127.25&minY=37.25&maxY=37.65&getType=json";
        try {
            String json = restClient.get()
                    .uri(url, properties.itsApiKey().trim())
                    .retrieve()
                    .body(String.class);
            if (json == null) {
                return;
            }
            JsonNode items = objectMapper.readTree(json).path("body").path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String apiLinkId = item.path("linkId").asText("");
                    double speed = item.path("speed").asDouble(-1.0);
                    for (RoadSectionSeed seed : seeds) {
                        if (seed.apiLinkId().equals(apiLinkId) && speed > 0.0) {
                            catalog.updateCachedSpeed(seed.linkId(), speed);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ITS trafficInfo 호출 실패: {}", e.getMessage());
        }
    }
}
