package com.sentinel.traffic.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.traffic.client.RoadNetworkCatalog.RoadSectionSeed;
import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 국토교통부 국가교통정보센터(ITS) 고속도로·일반국도(9개 노선) 수집 클라이언트.
 * 기동 시에는 쿼터를 소모하지 않고 캐시를 로드하며, 수동 새로고침 요청 시에만 실제 ITS 오픈API를 호출한다.
 */
@Component
public class ItsTrafficFeedClient implements TrafficFeedClient {

    private static final Logger log = LoggerFactory.getLogger(ItsTrafficFeedClient.class);

    private final PublicApiProperties properties;
    private final RoadNetworkCatalog catalog;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ItsTrafficFeedClient() {
        this(new PublicApiProperties(3000L, "", ""), new RoadNetworkCatalog(false));
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
        List<RoadSectionSeed> targets = seeds.stream()
                .filter(s -> targetLinkId == null || targetLinkId.isBlank() || s.linkId().equals(targetLinkId))
                .toList();

        if (targets.isEmpty()) {
            return new LiveRefreshResult(List.of(), 0);
        }

        int apiCalls = 0;
        if (properties.hasItsKey()) {
            // 동일 노선번호(routeType + routeNo + drcType)끼리 묶어 최소 횟수로 호출한다.
            Map<String, List<RoadSectionSeed>> byRoute = new LinkedHashMap<>();
            for (RoadSectionSeed seed : targets) {
                String routeKey = seed.itsRouteType() + ":" + seed.itsRouteNo() + ":" + seed.itsDrcType();
                byRoute.computeIfAbsent(routeKey, k -> new java.util.ArrayList<>()).add(seed);
            }
            for (List<RoadSectionSeed> routeGroup : byRoute.values()) {
                apiCalls++;
                fetchItsRouteAndUpdateCache(routeGroup);
            }
            catalog.persistDiskSpeedCache();
        }

        Instant now = Instant.now();
        List<RoadLinkTraffic> updated = targets.stream()
                .map(s -> catalog.toRoadLinkTraffic(s, now))
                .toList();
        return new LiveRefreshResult(updated, apiCalls);
    }

    private void fetchItsRouteAndUpdateCache(List<RoadSectionSeed> routeGroup) {
        RoadSectionSeed first = routeGroup.getFirst();
        double minX = routeGroup.stream().flatMap(s -> s.geometry().stream()).mapToDouble(GeoPoint::lng).min().orElse(126.75);
        double maxX = routeGroup.stream().flatMap(s -> s.geometry().stream()).mapToDouble(GeoPoint::lng).max().orElse(127.25);
        double minY = routeGroup.stream().flatMap(s -> s.geometry().stream()).mapToDouble(GeoPoint::lat).min().orElse(37.25);
        double maxY = routeGroup.stream().flatMap(s -> s.geometry().stream()).mapToDouble(GeoPoint::lat).max().orElse(37.65);

        String url = "https://openapi.its.go.kr:9443/trafficInfo"
                + "?apiKey={key}&type={type}&routeNo={routeNo}&drcType={drc}"
                + "&minX={minX}&maxX={maxX}&minY={minY}&maxY={maxY}&getType=json";
        try {
            String json = restClient.get()
                    .uri(
                            url,
                            properties.itsApiKey().trim(),
                            first.itsRouteType(),
                            first.itsRouteNo(),
                            first.itsDrcType(),
                            minX - 0.02,
                            maxX + 0.02,
                            minY - 0.02,
                            maxY + 0.02
                    )
                    .retrieve()
                    .body(String.class);
            if (json == null) {
                return;
            }
            JsonNode items = objectMapper.readTree(json).path("body").path("items");
            if (!items.isArray() || items.isEmpty()) {
                return;
            }

            DoubleSummaryStatistics routeStats = new DoubleSummaryStatistics();
            for (JsonNode item : items) {
                String apiLinkId = item.path("linkId").asText("");
                double speed = item.path("speed").asDouble(-1.0);
                if (speed > 0.0) {
                    routeStats.accept(speed);
                }
                for (RoadSectionSeed seed : routeGroup) {
                    if (seed.apiLinkId().equals(apiLinkId) && speed > 0.0) {
                        catalog.updateCachedSpeed(seed.linkId(), speed);
                    }
                }
            }

            if (routeStats.getCount() > 0) {
                double avgRouteSpeed = routeStats.getAverage();
                for (RoadSectionSeed seed : routeGroup) {
                    if (catalog.toRoadLinkTraffic(seed, Instant.now()).speedKph() <= 0.0) {
                        catalog.updateCachedSpeed(seed.linkId(), avgRouteSpeed);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("ITS trafficInfo 호출 실패 (routeNo={}): {}", first.itsRouteNo(), e.getMessage());
        }
    }
}
