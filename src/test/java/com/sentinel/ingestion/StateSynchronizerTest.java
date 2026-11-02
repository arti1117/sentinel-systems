package com.sentinel.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinel.domain.FeedStatus;
import com.sentinel.domain.LinkStateSnapshot;
import com.sentinel.domain.SourceType;
import com.sentinel.domain.TrafficFlowEvent;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 Fault-Injection Testbed for {@link StateSynchronizer}.
 *
 * <p>Remove {@code @Disabled} annotations as you implement the state-control methods in
 * {@link StateSynchronizer}.
 */
class StateSynchronizerTest {

    @Test
    @Disabled("Phase 1 core state control logic to be implemented by JY")
    @DisplayName("동일 (SourceType, eventId) 중복 패킷 수신 시 상태를 중복 갱신하지 않고 ignoredDuplicateCount를 증가시킨다")
    void duplicateTrafficPacketIsIdempotent() {
        StateSynchronizer sync = new StateSynchronizer();
        Instant t0 = Instant.parse("2026-11-02T06:00:00Z");
        TrafficFlowEvent event = new TrafficFlowEvent("PKT-1001", "LINK-01", 72.5, t0);

        LinkStateSnapshot first = sync.ingestTrafficFlow(event);
        LinkStateSnapshot second = sync.ingestTrafficFlow(event);

        assertThat(first.trafficStatus()).isEqualTo(FeedStatus.FRESH);
        assertThat(second.lastTrafficSpeedKph()).isEqualTo(72.5);
        assertThat(second.appliedEventCount()).isEqualTo(1L);
        assertThat(second.ignoredDuplicateCount()).isEqualTo(1L);
    }

    @Test
    @Disabled("Phase 1 core state control logic to be implemented by JY")
    @DisplayName("수신 시차(Out-of-order)로 인해 과거 관측 시각 패킷이 늦게 도착하면 최신 상태를 덮어쓰지 않고 거절한다")
    void outOfOrderPacketDoesNotOverwriteNewerState() {
        StateSynchronizer sync = new StateSynchronizer();
        Instant newer = Instant.parse("2026-11-02T06:05:00Z");
        Instant older = newer.minus(Duration.ofMinutes(3));

        sync.ingestTrafficFlow(new TrafficFlowEvent("PKT-NEW", "LINK-01", 35.0, newer));
        LinkStateSnapshot snap = sync.ingestTrafficFlow(
                new TrafficFlowEvent("PKT-OLD-DELAYED", "LINK-01", 95.0, older)
        );

        assertThat(snap.lastTrafficSpeedKph()).isEqualTo(35.0);
        assertThat(snap.lastTrafficObservedAt()).isEqualTo(newer);
        assertThat(snap.appliedEventCount()).isEqualTo(1L);
        assertThat(snap.rejectedOutOfOrderCount()).isEqualTo(1L);
    }

    @Test
    @Disabled("Phase 1 core state control logic to be implemented by JY")
    @DisplayName("외부 API 타임아웃 발생 시 직전 관측값과 시각은 보존하고 상태만 UNKNOWN_TIMEOUT으로 전이한다")
    void upstreamTimeoutPreservesLastObservationWithUnknownStatus() {
        StateSynchronizer sync = new StateSynchronizer();
        Instant t0 = Instant.parse("2026-11-02T06:00:00Z");
        sync.ingestTrafficFlow(new TrafficFlowEvent("PKT-2001", "LINK-01", 50.0, t0));

        LinkStateSnapshot degraded = sync.markSourceTimeout("LINK-01", SourceType.TRAFFIC_FLOW);

        assertThat(degraded.trafficStatus()).isEqualTo(FeedStatus.UNKNOWN_TIMEOUT);
        assertThat(degraded.lastTrafficSpeedKph()).isEqualTo(50.0);
        assertThat(degraded.lastTrafficObservedAt()).isEqualTo(t0);
    }
}
