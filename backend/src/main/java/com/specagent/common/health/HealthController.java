package com.specagent.common.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final boolean workerEnabled;

    public HealthController(
            @Value("${spec.agent.brain.worker.enabled:true}") boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        // Fail-safe: a process that serves the run API but has no worker can
        // never report fully healthy — runs would be accepted and then never
        // claimed (AGENT_WORKER_UNAVAILABLE).
        if (!workerEnabled) {
            return ResponseEntity.status(503).body(Map.of(
                "status", "DEGRADED",
                "service", "spec-agent",
                "reason", "AGENT_WORKER_UNAVAILABLE",
                "timestamp", Instant.now().toString()
            ));
        }
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "spec-agent",
            "worker", "ENABLED",
            "timestamp", Instant.now().toString()
        ));
    }
}
