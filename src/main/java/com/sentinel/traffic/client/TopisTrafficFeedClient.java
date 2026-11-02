package com.sentinel.traffic.client;

import com.sentinel.traffic.config.PublicApiProperties;
import com.sentinel.traffic.domain.GeoPoint;
import com.sentinel.traffic.domain.RoadLinkTraffic;
import com.sentinel.traffic.domain.RoadType;
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
 * 서울 열린데이터광장(TOPIS) 서울시 도시고속도로·주요 간선도로 실시간 소통 데이터 수집 클라이언트.
 * SENTINEL_TOPIS_API_KEY가 설정되면 실시간 TrafficInfo 오픈API를 호출하고, 미설정 시 기본 스냅샷으로 동작한다.
 */
@Component
public class TopisTrafficFeedClient implements TrafficFeedClient {

    private static final Logger log = LoggerFactory.getLogger(TopisTrafficFeedClient.class);
    private static final Pattern PRCS_SPD_PATTERN = Pattern.compile("<prcs_spd>([0-9.]+)</prcs_spd>");

    private final PublicApiProperties properties;
    private final RestClient restClient;

    private record TopisSectionSpec(
            String linkId,
            String topisApiLinkId,
            String roadName,
            String sectionName,
            RoadType roadType,
            double fallbackSpeedKph,
            List<GeoPoint> geometry
    ) {
    }

    private static final List<TopisSectionSpec> MONITORED_SECTIONS = List.of(
            new TopisSectionSpec(
                    "TOPIS-UHWY-088-01",
                    "1210001800",
                    "올림픽대로",
                    "한남대교남단 → 성수대교남단",
                    RoadType.URBAN_HIGHWAY,
                    19.5,
                    List.of(
                            new GeoPoint(37.5192, 127.0128),
                            new GeoPoint(37.5270, 127.0285),
                            new GeoPoint(37.5338, 127.0442)
                    )
            ),
            new TopisSectionSpec(
                    "TOPIS-UHWY-070-01",
                    "1020000400",
                    "강변북로",
                    "마포대교북단 → 한강대교북단",
                    RoadType.URBAN_HIGHWAY,
                    36.0,
                    List.of(
                            new GeoPoint(37.5365, 126.9412),
                            new GeoPoint(37.5280, 126.9535),
                            new GeoPoint(37.5218, 126.9665)
                    )
            ),
            new TopisSectionSpec(
                    "TOPIS-UHWY-030-01",
                    "1120000100",
                    "내부순환로",
                    "성산램프 → 홍은램프",
                    RoadType.URBAN_HIGHWAY,
                    58.0,
                    List.of(
                            new GeoPoint(37.5648, 126.9032),
                            new GeoPoint(37.5785, 126.9210),
                            new GeoPoint(37.5892, 126.9420)
                    )
            ),
            new TopisSectionSpec(
                    "TOPIS-ART-101-01",
                    "1220003800",
                    "테헤란로",
                    "삼성역사거리 → 역삼역사거리",
                    RoadType.URBAN_ARTERIAL,
                    12.0,
                    List.of(
                            new GeoPoint(37.5088, 127.0631),
                            new GeoPoint(37.5045, 127.0490),
                            new GeoPoint(37.5006, 127.0365)
                    )
            ),
            new TopisSectionSpec(
                    "TOPIS-ART-102-01",
                    "1220000100",
                    "강남대로",
                    "강남역사거리 → 신논현역교차로",
                    RoadType.URBAN_ARTERIAL,
                    18.5,
                    List.of(
                            new GeoPoint(37.4979, 127.0276),
                            new GeoPoint(37.5045, 127.0250)
                    )
            ),
            new TopisSectionSpec(
                    "TOPIS-ART-103-01",
                    "1000000300",
                    "세종대로",
                    "광화문삼거리 → 시청역교차로",
                    RoadType.URBAN_ARTERIAL,
                    29.0,
                    List.of(
                            new GeoPoint(37.5759, 126.9768),
                            new GeoPoint(37.5663, 126.9779)
                    )
            )
    );

    public TopisTrafficFeedClient() {
        this(new PublicApiProperties(3000L, "", ""));
    }

    @Autowired
    public TopisTrafficFeedClient(PublicApiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    @Override
    public TrafficSource source() {
        return TrafficSource.TOPIS;
    }

    @Override
    public List<RoadLinkTraffic> fetchLatestTraffic() {
        Instant now = Instant.now();
        List<RoadLinkTraffic> results = new ArrayList<>(MONITORED_SECTIONS.size());

        for (TopisSectionSpec spec : MONITORED_SECTIONS) {
            double speedKph = spec.fallbackSpeedKph();
            if (properties.hasTopisKey()) {
                Double liveSpeed = fetchLiveSpeedFromSeoulApi(spec.topisApiLinkId());
                if (liveSpeed != null) {
                    speedKph = liveSpeed;
                }
            }
            results.add(RoadLinkTraffic.of(
                    TrafficSource.TOPIS,
                    spec.linkId(),
                    spec.roadName(),
                    spec.sectionName(),
                    spec.roadType(),
                    speedKph,
                    spec.geometry(),
                    now
            ));
        }
        return results;
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
