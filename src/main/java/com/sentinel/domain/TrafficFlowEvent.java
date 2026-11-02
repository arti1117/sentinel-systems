package com.sentinel.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Normalized road link speed/volume telemetry packet (1~5 min cycle).
 */
public record TrafficFlowEvent(
        @NotBlank String eventId,
        @NotBlank String linkId,
        @DecimalMin("0.0") @DecimalMax("250.0") double speedKph,
        @NotNull Instant observedAt
) {
}
