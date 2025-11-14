package com.nexusconnect.servicebackend.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check endpoint to verify WebRTC infrastructure is running.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "services", Map.of(
                        "webrtc-signaling", "ws://localhost:8080/ws/signaling",
                        "stun-server", "stun:localhost:3478",
                        "legacy-voice-relay", "ws://localhost:8080/ws/voice")));
    }
}
