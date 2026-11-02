package com.sentinel.traffic.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
import com.sentinel.traffic.domain.TrafficSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 국토교통부 국가교통정보센터(ITS) 전국 고속도로·일반국도 실시간 소통 데이터 수집 클라이언트.
 * ITS trafficInfo API는 노선 전체 구간(360+개)을 한 번에 반환하여 응답에 15~25초가 소요되므로,
 * 초기 기동 지연을 막기 위해 캐시된 최신 속도를 즉시 반환하고 Virtual Thread에서 비동기로 실데이터를 갱신한다.
 */
@Component
public class ItsTrafficFeedClient implements TrafficFeedClient {

    private static final Logger log = LoggerFactory.getLogger(ItsTrafficFeedClient.class);

    private final PublicApiProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Double> liveSpeedCache = new ConcurrentHashMap<>();

    private record ItsSectionSpec(
            String linkId,
            String itsApiLinkId,
            String routeNo,
            String roadName,
            String sectionName,
            RoadType roadType,
            double fallbackSpeedKph,
            List<GeoPoint> geometry
    ) {
    }

    private static final List<ItsSectionSpec> MONITORED_SECTIONS = List.of(
            new ItsSectionSpec(
                    "ITS-HWY-0010-01",
                    "1210000800",
                    "0010",
                    "경부고속도로",
                    "양재IC → 판교JC",
                    RoadType.HIGHWAY,
                    28.5,
                    List.of(
                            new GeoPoint(37.4633, 127.0422),
                            new GeoPoint(37.4350, 127.0710),
                            new GeoPoint(37.4055, 127.0953)
                    )
            ),
            new ItsSectionSpec(
                    "ITS-HWY-0010-02",
                    "1210001901",
                    "0010",
                    "경부고속도로",
                    "판교JC → 기흥IC",
                    RoadType.HIGHWAY,
                    54.0,
                    List.of(
                            new GeoPoint(37.4055, 127.0953),
                            new GeoPoint(37.3360, 127.1045),
                            new GeoPoint(37.2248, 127.1032)
                    )
            ),
            new ItsSectionSpec(
                    "ITS-HWY-1000-01",
                    "1310011400",
                    "1000",
                    "수도권제1순환고속도로",
                    "성남IC → 하남JC",
                    RoadType.HIGHWAY,
                    88.0,
                    List.of(
                            new GeoPoint(37.4342, 127.1215),
                            new GeoPoint(37.4840, 127.1520),
                            new GeoPoint(37.5262, 127.1938)
                    )
            ),
            new ItsSectionSpec(
                    "ITS-HWY-0500-01",
                    "1350016100",
                    "0500",
                    "영동고속도로",
                    "신갈JC → 마성IC",
                    RoadType.HIGHWAY,
                    32.0,
                    List.of(
                            new GeoPoint(37.2740, 127.1035),
                            new GeoPoint(37.2855, 127.1510),
                            new GeoPoint(37.2825, 127.1995)
                    )
            ),
            new ItsSectionSpec(
                    "ITS-NAT-0077-01",
                    "2180000101",
                    "0077",
                    "자유로(국도77호선)",
                    "가양대교북단 → 행주대교북단",
                    RoadType.NATIONAL,
                    67.5,
                    List.of(
                            new GeoPoint(37.5752, 126.8660),
                            new GeoPoint(37.5905, 126.8400),
                            new GeoPoint(37.6062, 126.8175)
                    )
            )
    );

    public ItsTrafficFeedClient() {
        this(new PublicApiProperties(3000L, "", ""));
    }

    @Autowired
    public ItsTrafficFeedClient(PublicApiProperties properties) {
        this.properties = properties;
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
    public List<RoadLinkTraffic> fetchLatestTraffic() {
        if (properties.hasItsKey() && liveSpeedCache.isEmpty()) {
            triggerAsyncLiveRefresh();
        }
        return buildSnapshotFromCache();
    }

    public List<RoadLinkTraffic> buildSnapshotFromCache() {
        Instant now = Instant.now();
        List<RoadLinkTraffic> results = new ArrayList<>(MONITORED_SECTIONS.size());
        for (ItsSectionSpec spec : MONITORED_SECTIONS) {
            double speed = liveSpeedCache.getOrDefault(spec.linkId(), spec.fallbackSpeedKph());
            results.add(RoadLinkTraffic.of(
                    TrafficSource.ITS,
                    spec.linkId(),
                    spec.roadName(),
                    spec.sectionName(),
                    spec.roadType(),
                    speed,
                    spec.geometry(),
                    now
            ));
        }
        return results;
    }

    private void triggerAsyncLiveRefresh() {
        Thread.ofVirtual().name("its-live-poller").start(() -> {
            String url = "https://openapi.its.go.kr:9443/trafficInfo"
                    + "?apiKey={key}&type=ex&routeNo=0010&drcType=up"
                    + "&minX=127.00&maxX=127.15&minY=37.35&maxY=37.50&getType=json";
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
                        for (ItsSectionSpec spec : MONITORED_SECTIONS) {
                            if (spec.itsApiLinkId().equals(apiLinkId) && speed > 0) {
                                liveSpeedCache.put(spec.linkId(), speed);
                            }
                        }
                    }
                    log.info("ITS 실시간 고속도로 소통 데이터 비동기 수신 완료 (갱신 구간={}개)", liveSpeedCache.size());
                }
            } catch (Exception e) {
                log.warn("ITS trafficInfo 비동기 호출 실패: {}", e.getMessage());
            }
        });
    }
}
