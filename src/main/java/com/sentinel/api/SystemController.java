package com.sentinel.api;

import com.sentinel.domain.LinkStateSnapshot;
import com.sentinel.ingestion.StateSynchronizer;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational health and road link state inspection REST endpoints.
 */
@RestController
public class SystemController {

    private final StateSynchronizer synchronizer;

    public SystemController(StateSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "runtime", "java-" + Runtime.version().feature()
        );
    }

    @GetMapping("/api/v1/links/{linkId}/state")
    public LinkStateSnapshot getLinkState(@PathVariable String linkId) {
        return synchronizer.getLinkSnapshot(linkId);
    }
}
