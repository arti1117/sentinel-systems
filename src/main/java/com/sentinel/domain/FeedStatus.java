package com.sentinel.domain;

/**
 * Explicit liveness and consistency state of an external data feed on a road link.
 */
public enum FeedStatus {
    FRESH,
    STALE,
    UNKNOWN_TIMEOUT
}
