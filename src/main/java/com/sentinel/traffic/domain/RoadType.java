package com.sentinel.traffic.domain;

/**
 * 도로 기능별 분류 및 소통 등급 판정 기준 속도(km/h).
 * 고속도로(HIGHWAY)는 원활 80 이상 / 서행 40 이상, 일반국도(NATIONAL)는 원활 60 이상 / 서행 30 이상,
 * 도시고속도로(URBAN_HIGHWAY)는 원활 50 이상 / 서행 30 이상, 도심간선도로(URBAN_ARTERIAL)는 원활 25 이상 / 서행 15 이상을 기준으로 한다.
 */
public enum RoadType {
    HIGHWAY(80.0, 40.0),
    NATIONAL(60.0, 30.0),
    URBAN_HIGHWAY(50.0, 30.0),
    URBAN_ARTERIAL(25.0, 15.0);

    private final double smoothThresholdKph;
    private final double slowThresholdKph;

    RoadType(double smoothThresholdKph, double slowThresholdKph) {
        this.smoothThresholdKph = smoothThresholdKph;
        this.slowThresholdKph = slowThresholdKph;
    }

    public double smoothThresholdKph() {
        return smoothThresholdKph;
    }

    public double slowThresholdKph() {
        return slowThresholdKph;
    }

    /**
     * 관측 평균 속도를 기준으로 소통 상태 등급을 판정한다.
     * 루프 검지기 오류 등 0 이하 또는 NaN 속도는 UNKNOWN으로 분류한다.
     */
    public CongestionLevel classify(double speedKph) {
        if (Double.isNaN(speedKph) || speedKph <= 0.0) {
            return CongestionLevel.UNKNOWN;
        }
        if (speedKph >= smoothThresholdKph) {
            return CongestionLevel.SMOOTH;
        }
        if (speedKph >= slowThresholdKph) {
            return CongestionLevel.SLOW;
        }
        return CongestionLevel.CONGESTED;
    }
}
