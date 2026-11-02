package com.sentinel.ingestion;

import com.sentinel.domain.LinkStateSnapshot;
import com.sentinel.domain.SourceType;
import com.sentinel.domain.TrafficFlowEvent;
import com.sentinel.domain.WeatherObservation;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Phase 1 State Synchronizer contract.
 *
 * <p>Core business and state-control invariants to be implemented by JY:
 * <ol>
 *   <li><b>Idempotency:</b> Duplicate packets with the same {@code (SourceType, eventId)} must be
 *       ignored without mutating link telemetry or double-counting applied events
 *       ({@code ignoredDuplicateCount++}).</li>
 *   <li><b>Temporal Watermark (Out-of-Order Guard):</b> If an incoming packet has
 *       {@code observedAt} older than the link's {@code last*ObservedAt} for that feed, reject the
 *       state overwrite and increment {@code rejectedOutOfOrderCount}.</li>
 *   <li><b>Explicit Unknown / Timeout State:</b> When an upstream feed times out
 *       ({@link #markSourceTimeout(String, SourceType)}), transition that feed's status to
 *       {@code FeedStatus.UNKNOWN_TIMEOUT} while preserving the last known observation values and
 *       timestamp for audit and fallback inspection.</li>
 * </ol>
 */
@Component
public class StateSynchronizer {

    private final Map<String, LinkStateSnapshot> links = new ConcurrentHashMap<>();
    private final Set<EventKey> seenEvents = ConcurrentHashMap.newKeySet();

    public record EventKey(SourceType source, String eventId) {
    }

    /**
     * Apply a traffic flow telemetry packet to the target road link state.
     *
     * <p>TODO(JY): Implement idempotency guard, out-of-order watermark comparison, and atomic
     * {@code FRESH} state transition using {@code links.compute(...)}.
     */
    public LinkStateSnapshot ingestTrafficFlow(TrafficFlowEvent event) {
        throw new UnsupportedOperationException(
                "Phase 1 traffic flow state control to be implemented by JY"
        );
    }

    /**
     * Apply a weather observation packet to the target road link state.
     *
     * <p>TODO(JY): Implement idempotency guard, out-of-order watermark comparison, and atomic
     * {@code FRESH} state transition.
     */
    public LinkStateSnapshot ingestWeather(WeatherObservation event) {
        throw new UnsupportedOperationException(
                "Phase 1 weather state control to be implemented by JY"
        );
    }

    /**
     * Record an explicit upstream timeout ({@code UNKNOWN_TIMEOUT}) without erasing prior
     * telemetry.
     *
     * <p>TODO(JY): Transition the specified feed status on {@code linkId} to
     * {@code UNKNOWN_TIMEOUT} while preserving the last known observation fields.
     */
    public LinkStateSnapshot markSourceTimeout(String linkId, SourceType source) {
        throw new UnsupportedOperationException(
                "Phase 1 timeout state transition to be implemented by JY"
        );
    }

    /**
     * Return the synchronized snapshot for {@code linkId} and evaluate staleness if
     * {@code referenceTime} is provided.
     */
    public LinkStateSnapshot getLinkSnapshot(String linkId, Instant referenceTime) {
        return links.getOrDefault(linkId, LinkStateSnapshot.empty(linkId));
    }

    public LinkStateSnapshot getLinkSnapshot(String linkId) {
        return getLinkSnapshot(linkId, null);
    }
}
