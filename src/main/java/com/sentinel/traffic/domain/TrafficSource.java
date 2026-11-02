package com.sentinel.traffic.domain;

/**
 * 실시간 교통 공공데이터 제공처 구분.
 * ITS: 국토교통부 국가교통정보센터 (전국 고속도로 · 일반국도)
 * TOPIS: 서울 열린데이터광장 (서울시 도시고속도로 · 주요 간선도로)
 */
public enum TrafficSource {
    ITS,
    TOPIS
}
