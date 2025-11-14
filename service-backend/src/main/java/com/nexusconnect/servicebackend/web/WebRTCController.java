package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.webrtc.StunServer;
import com.nexusconnect.servicebackend.webrtc.WebRTCSignalingHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API endpoints for WebRTC support.
 * Provides STUN server information and signaling server status.
 */
@RestController
@RequestMapping("/api/webrtc")
public class WebRTCController {

    private final StunServer stunServer;
    private final WebRTCSignalingHandler signalingHandler;

    public WebRTCController(StunServer stunServer, WebRTCSignalingHandler signalingHandler) {
        this.stunServer = stunServer;
        this.signalingHandler = signalingHandler;
    }

    /**
     * Get STUN server configuration for WebRTC clients.
     * Clients use this endpoint to discover the STUN server URL.
     */
    @GetMapping("/stun-config")
    public ResponseEntity<Map<String, Object>> getStunConfig() {
        var stats = stunServer.getStats();

        return ResponseEntity.ok(Map.of(
                "stunServers", Map.of(
                        "urls", "stun:localhost:" + stats.port(),
                        "enabled", stats.running()),
                "signalingUrl", "ws://localhost:8080/ws/signaling"));
    }

    /**
     * Get STUN server statistics.
     */
    @GetMapping("/stun-stats")
    public ResponseEntity<StunServer.StunStats> getStunStats() {
        return ResponseEntity.ok(stunServer.getStats());
    }

    /**
     * Get signaling server status.
     */
    @GetMapping("/signaling-status")
    public ResponseEntity<Map<String, Object>> getSignalingStatus() {
        return ResponseEntity.ok(Map.of(
                "connectedUsers", signalingHandler.getConnectedUserCount(),
                "endpoint", "/ws/signaling"));
    }

    /**
     * Check if a specific user is connected to the signaling server.
     */
    @GetMapping("/signaling-status/{username}")
    public ResponseEntity<Map<String, Object>> checkUserConnection(@PathVariable String username) {
        boolean connected = signalingHandler.isUserConnected(username);

        return ResponseEntity.ok(Map.of(
                "username", username,
                "connected", connected));
    }
}
