package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.nio.NioChatServer;
import com.nexusconnect.servicebackend.voice.VoiceSessionManager;
import com.nexusconnect.servicebackend.web.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * REST API endpoints for P2P voice communication.
 * Handles voice session initiation, status queries, and termination.
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
@RestController
@RequestMapping("/api/voice")
@Validated
public class VoiceController {
    private final NioChatServer nioChatServer;
    private final VoiceSessionManager voiceSessionManager;

    public VoiceController(NioChatServer nioChatServer, VoiceSessionManager voiceSessionManager) {
        this.nioChatServer = nioChatServer;
        this.voiceSessionManager = voiceSessionManager;
    }

    /**
     * Initiates a P2P voice session between two peers.
     * 
     * The initiator contacts the server with their local UDP port.
     * The server looks up the target peer and returns their IP and UDP port.
     * Both peers then establish a direct UDP connection for audio streaming.
     * 
     * @param request     Voice initiation request containing initiator, target, and
     *                    local port
     * @param httpRequest HTTP servlet request for extracting client IP
     * @return VoiceInitResponse with target peer's IP and port, or error message
     */
    @PostMapping("/initiate")
    public ResponseEntity<VoiceInitResponse> initiateVoiceSession(
            @Valid @RequestBody VoiceInitRequest request,
            HttpServletRequest httpRequest) {

        String initiator = request.initiator();
        String target = request.target();
        Integer localPort = request.localUdpPort();

        // Validate input
        if (initiator == null || target == null || localPort == null || localPort <= 0) {
            return ResponseEntity.badRequest()
                    .body(VoiceInitResponse.failure("Invalid request parameters"));
        }

        // Prevent calling yourself
        if (initiator.equals(target)) {
            return ResponseEntity.badRequest()
                    .body(VoiceInitResponse.failure("Cannot initiate voice session with yourself"));
        }

        // Get initiator's IP (support X-Forwarded-For header for proxies)
        String initiatorIp = Optional.ofNullable(httpRequest.getHeader("X-Forwarded-For"))
                .map(v -> v.split(",", 2)[0])
                .orElseGet(httpRequest::getRemoteAddr);

        // Find target peer details from the NIO server
        Optional<NioChatServer.Peer> targetPeerOpt = nioChatServer.findPeer(target);
        if (targetPeerOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(VoiceInitResponse.failure("Target peer not found or offline"));
        }

        NioChatServer.Peer targetPeer = targetPeerOpt.get();

        // Validate target has advertised a UDP port for voice
        if (targetPeer.voiceUdp() <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(VoiceInitResponse.failure("Target peer has not advertised a voice UDP port"));
        }

        // Create a voice session
        long sessionId = voiceSessionManager.initiateSession(
                initiator,
                target,
                initiatorIp,
                localPort,
                targetPeer.ip(),
                targetPeer.voiceUdp());

        if (sessionId < 0) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(VoiceInitResponse.failure("Failed to create voice session"));
        }

        // Return target's connection details to initiator
        return ResponseEntity.ok(VoiceInitResponse.success(
                targetPeer.ip(),
                targetPeer.voiceUdp(),
                sessionId));
    }

    /**
     * Gets status of an active voice session.
     * 
     * @param sessionId ID of the voice session
     * @return Session details (duration, participants, etc.)
     */
    @GetMapping("/status/{sessionId}")
    public ResponseEntity<?> getSessionStatus(@PathVariable long sessionId) {
        VoiceSessionManager.VoiceSession session = voiceSessionManager.getSession(sessionId);

        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(java.util.Map.of(
                "sessionId", session.sessionId(),
                "initiator", session.initiator(),
                "target", session.target(),
                "initiatorIp", session.initiatorIp(),
                "initiatorPort", session.initiatorPort(),
                "targetIp", session.targetIp(),
                "targetPort", session.targetPort(),
                "createdAt", session.createdAt(),
                "durationMs", session.getDurationMs()));
    }

    /**
     * Terminates an active voice session.
     * Should be called by either peer when they end the call.
     * 
     * @param sessionId ID of the voice session to terminate
     * @return 204 No Content if successful, 404 if session not found
     */
    @PostMapping("/terminate/{sessionId}")
    public ResponseEntity<Void> terminateVoiceSession(@PathVariable long sessionId) {
        boolean terminated = voiceSessionManager.terminateSession(sessionId);

        if (terminated) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Gets audio configuration for the voice channel.
     * Ensures both peers use the same audio parameters.
     * 
     * @return AudioStreamConfig with standard VoIP settings
     */
    @GetMapping("/config")
    public ResponseEntity<AudioStreamConfig> getAudioConfig() {
        return ResponseEntity.ok(AudioStreamConfig.createDefault());
    }
}
