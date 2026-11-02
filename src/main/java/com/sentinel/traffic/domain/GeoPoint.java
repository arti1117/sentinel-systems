package com.sentinel.traffic.domain;

/**
 * 지도 렌더링 및 공간 연산용 WGS84 위경도 좌표.
 */
public record GeoPoint(
        double lat,
        double lng
) {
}
