package com.sentinel.traffic.domain;

/**
 * 도로 구간 소통 상태 등급 (원활, 서행, 정체, 센서 결측/점검).
 */
public enum CongestionLevel {
    SMOOTH,
    SLOW,
    CONGESTED,
    UNKNOWN
}
