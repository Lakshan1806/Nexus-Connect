package com.nexusconnect.servicebackend.web.dto;

/**
 * Response after initiating a peer-to-peer voice session.
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
public record VoiceInitResponse(
        boolean success,
        String message,
        String targetIp,
        Integer targetPort,
        Long sessionId) {
    public static VoiceInitResponse success(String targetIp, Integer targetPort, Long sessionId) {
        return new VoiceInitResponse(true, "Voice session initiated", targetIp, targetPort, sessionId);
    }

    public static VoiceInitResponse failure(String message) {
        return new VoiceInitResponse(false, message, null, null, null);
    }
}
