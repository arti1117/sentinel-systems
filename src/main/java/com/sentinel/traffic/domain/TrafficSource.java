package com.sentinel.traffic.domain;

/**
 * Public traffic data provider source.
 *
 * <ul>
 *   <li>{@link #ITS}: 국토교통부 국가교통정보센터 (전국 고속도로 · 일반국도)</li>
 *   <li>{@link #TOPIS}: 서울 열린데이터광장 TOPIS (서울시 도시고속도로 · 주요 간선도로)</li>
 * </ul>
 */
public enum TrafficSource {
    ITS,
    TOPIS
}
