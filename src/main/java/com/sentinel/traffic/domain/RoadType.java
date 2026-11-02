package com.sentinel.traffic.domain;

/**
 * Road functional classification and speed thresholds (km/h) for congestion grading.
 *
 * <ul>
 *   <li>{@link #HIGHWAY} (고속도로): 원활 &ge; 80, 서행 &ge; 40, 정체 &lt; 40</li>
 *   <li>{@link #NATIONAL} (일반국도): 원활 &ge; 60, 서행 &ge; 30, 정체 &lt; 30</li>
 *   <li>{@link #URBAN_HIGHWAY} (도시고속도로: 올림픽대로·강변북로 등): 원활 &ge; 50, 서행 &ge; 30, 정체 &lt; 30</li>
 *   <li>{@link #URBAN_ARTERIAL} (도심간선도로: 강남대로·테헤란로 등): 원활 &ge; 25, 서행 &ge; 15, 정체 &lt; 15</li>
 * </ul>
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
     * Classify congestion level from observed average speed.
     * Sensor readings {@code <= 0.0} or {@code NaN} indicate detector failure ({@link CongestionLevel#UNKNOWN}).
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
