package com.sentinel.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Real-time traffic incident/hazard event.
 */
public record IncidentEvent(
        @NotBlank String eventId,
        @NotBlank String linkId,
        @NotBlank String incidentType,
        @Min(1) @Max(5) int severity,
        @NotNull Instant observedAt
) {
}
