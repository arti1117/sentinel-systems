package com.sentinel.traffic.domain;

/**
 * WGS84 geographic coordinate (latitude, longitude) for map rendering and spatial indexing.
 */
public record GeoPoint(
        double lat,
        double lng
) {
}
