package com.sentinel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External public API SLA timeout and staleness threshold configuration.
 */
@ConfigurationProperties(prefix = "sentinel.ingestion")
public record IngestionProperties(
        long apiTimeoutMs,
        long trafficStaleSeconds,
        long weatherStaleSeconds
) {
}
