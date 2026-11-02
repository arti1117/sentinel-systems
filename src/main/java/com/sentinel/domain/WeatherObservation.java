package com.sentinel.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Normalized ultra-short-term weather observation mapped to a road link (10 min cycle).
 */
public record WeatherObservation(
        @NotBlank String eventId,
        @NotBlank String linkId,
        @DecimalMin("0.0") double precipitationMmHr,
        @DecimalMin("-50.0") @DecimalMax("80.0") double roadSurfaceTempC,
        @Min(0) @Max(50000) int visibilityM,
        @NotNull Instant observedAt
) {
}
