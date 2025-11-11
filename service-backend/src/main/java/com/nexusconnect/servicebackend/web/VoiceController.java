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

    /**
     * Gets all incoming calls for the current user.
     * 
     * @param user Username to get incoming calls for
     * @return List of incoming call sessions (RINGING state)
     */
    @GetMapping("/incoming")
    public ResponseEntity<?> getIncomingCalls(@RequestParam String user) {
        var incomingCalls = voiceSessionManager.getIncomingCalls(user);

        var response = incomingCalls.stream()
                .map(session -> java.util.Map.of(
                        "sessionId", session.sessionId(),
                        "caller", session.initiator(),
                        "callerIp", session.initiatorIp(),
                        "callerPort", session.initiatorPort(),
                        "state", session.state()))
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * Accepts an incoming call.
     * 
     * Request body should contain:
     * - accepter: Username of the person accepting the call
     * - localUdpPort: Their local UDP port for audio
     * 
     * @param sessionId ID of the voice session to accept
     * @param body      Request body with accepter and localUdpPort
     * @return Updated session details with both peers' connection info
     */
    @PostMapping("/accept/{sessionId}")
    public ResponseEntity<?> acceptCall(@PathVariable long sessionId,
            @RequestBody java.util.Map<String, Object> body) {
        String accepter = (String) body.get("accepter");
        Object portObj = body.get("localUdpPort");

        Integer localPort = null;
        if (portObj instanceof Integer) {
            localPort = (Integer) portObj;
        } else if (portObj instanceof Number) {
            localPort = ((Number) portObj).intValue();
        }

        if (accepter == null || localPort == null || localPort <= 0) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Missing or invalid accepter or localUdpPort"));
        }

        VoiceSessionManager.VoiceSession session = voiceSessionManager.acceptSession(sessionId, accepter, localPort);

        if (session == null) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Cannot accept this call"));
        }

        return ResponseEntity.ok(java.util.Map.of(
                "sessionId", session.sessionId(),
                "state", session.state(),
                "initiator", session.initiator(),
                "target", session.target(),
                "initiatorIp", session.initiatorIp(),
                "initiatorPort", session.initiatorPort(),
                "targetIp", session.targetIp(),
                "targetPort", session.targetPort(),
                "connectedAt", session.acceptedAt()));
    }

    /**
     * Rejects an incoming call.
     * 
     * @param sessionId ID of the voice session to reject
     * @param user      Username of the person rejecting the call
     * @return 204 No Content if successful
     */
    @PostMapping("/reject/{sessionId}")
    public ResponseEntity<?> rejectCall(@PathVariable long sessionId,
            @RequestParam String user) {
        boolean rejected = voiceSessionManager.rejectSession(sessionId, user);

        if (!rejected) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Cannot reject this call"));
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * Stores the WebRTC SDP offer from the initiator.
     * Called by the initiator after creating the offer.
     * 
     * @param sessionId ID of the voice session
     * @param request   Request body containing the SDP offer
     * @return 200 OK
     */
    @PostMapping("/sdp/offer/{sessionId}")
    public ResponseEntity<?> storeSdpOffer(@PathVariable long sessionId,
            @RequestBody java.util.Map<String, String> request) {
        String sdpOffer = request.get("sdp");
        if (sdpOffer == null || sdpOffer.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Missing SDP offer"));
        }

        VoiceSessionManager.VoiceSession session = voiceSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        session.setInitiatorSdpOffer(sdpOffer);
        return ResponseEntity.ok(java.util.Map.of("status", "offer_stored"));
    }

    /**
     * Retrieves the WebRTC SDP offer stored by the initiator.
     * Called by the target to get the offer.
     * 
     * @param sessionId ID of the voice session
     * @return 200 OK with SDP offer, or 404 if offer not ready
     */
    @GetMapping("/sdp/offer/{sessionId}")
    public ResponseEntity<?> getSdpOffer(@PathVariable long sessionId) {
        VoiceSessionManager.VoiceSession session = voiceSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        if (!session.isInitiatorOfferReady()) {
            // Offer not ready yet - return 204 No Content for polling
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(java.util.Map.of(
                "sdp", session.getInitiatorSdpOffer()));
    }

    /**
     * Stores the WebRTC SDP answer from the target.
     * Called by the target after creating the answer.
     * 
     * @param sessionId ID of the voice session
     * @param request   Request body containing the SDP answer
     * @return 200 OK
     */
    @PostMapping("/sdp/answer/{sessionId}")
    public ResponseEntity<?> storeSdpAnswer(@PathVariable long sessionId,
            @RequestBody java.util.Map<String, String> request) {
        String sdpAnswer = request.get("sdp");
        if (sdpAnswer == null || sdpAnswer.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", "Missing SDP answer"));
        }

        VoiceSessionManager.VoiceSession session = voiceSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        session.setTargetSdpAnswer(sdpAnswer);
        return ResponseEntity.ok(java.util.Map.of("status", "answer_stored"));
    }

    /**
     * Retrieves the WebRTC SDP answer stored by the target.
     * Called by the initiator to get the answer.
     * 
     * @param sessionId ID of the voice session
     * @return 200 OK with SDP answer, or 204 if answer not ready
     */
    @GetMapping("/sdp/answer/{sessionId}")
    public ResponseEntity<?> getSdpAnswer(@PathVariable long sessionId) {
        VoiceSessionManager.VoiceSession session = voiceSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        if (!session.isTargetAnswerReady()) {
            // Answer not ready yet - return 204 No Content for polling
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(java.util.Map.of(
                "sdp", session.getTargetSdpAnswer()));
    }
}
