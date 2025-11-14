package com.nexusconnect.servicebackend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages P2P voice sessions between two clients.
 * Handles session lifecycle, peer details exchange, and session cleanup.
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 * 
 * Updated to support server-relay model for WebSocket-based voice streaming.
 */
@Component
public class VoiceSessionManager {
    private static final Logger log = LoggerFactory.getLogger(VoiceSessionManager.class);
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    // Map of session ID to VoiceSession
    private final ConcurrentHashMap<Long, VoiceSession> activeSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "voice-session-cleanup");
        t.setDaemon(true);
        return t;
    });
    private long sessionIdCounter = System.currentTimeMillis();

    public VoiceSessionManager() {
        // Start periodic cleanup of timed-out sessions
        sessionCleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredSessions,
                SESSION_TIMEOUT_MS,
                SESSION_TIMEOUT_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Initiates a new voice session between two peers.
     * 
     * @param initiator     Username of the peer initiating the session
     * @param target        Username of the peer being called
     * @param initiatorIp   IP address of the initiator
     * @param initiatorPort UDP port of the initiator for audio
     * @param targetIp      IP address of the target
     * @param targetPort    UDP port of the target for audio
     * @return Session ID, or -1 if failed
     */
    public synchronized long initiateSession(String initiator, String target,
            String initiatorIp, int initiatorPort,
            String targetIp, int targetPort) {
        if (initiator == null || target == null || initiatorIp == null || targetIp == null) {
            log.warn("Invalid parameters for voice session initiation");
            return -1;
        }

        long sessionId = ++sessionIdCounter;
        VoiceSession session = new VoiceSession(
                sessionId,
                initiator,
                target,
                initiatorIp,
                initiatorPort,
                targetIp,
                targetPort,
                System.currentTimeMillis());

        activeSessions.put(sessionId, session);
        log.info("Voice session {} initiated: {} -> {} ({}:{})",
                sessionId, initiator, target, targetIp, targetPort);

        return sessionId;
    }

    /**
     * Gets an active voice session by ID.
     */
    public VoiceSession getSession(long sessionId) {
        VoiceSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.updateLastActivity();
        }
        return session;
    }

    /**
     * Terminates a voice session.
     */
    public boolean terminateSession(long sessionId) {
        VoiceSession session = activeSessions.remove(sessionId);
        if (session != null) {
            log.info("Voice session {} terminated: {} <-> {}",
                    sessionId, session.initiator(), session.target());
            return true;
        }
        return false;
    }

    /**
     * Gets all active sessions for a user (either as initiator or target).
     */
    public List<VoiceSession> getUserSessions(String username) {
        return activeSessions.values().stream()
                .filter(s -> s.initiator().equals(username) || s.target().equals(username))
                .toList();
    }

    /**
     * Gets all incoming calls for a specific user (where they are the target).
     */
    public List<VoiceSession> getIncomingCalls(String user) {
        return activeSessions.values().stream()
                .filter(s -> s.target().equals(user))
                .filter(s -> "RINGING".equals(s.state()))
                .toList();
    }

    /**
     * Accepts an incoming call by updating session state and setting target port.
     */
    public synchronized VoiceSession acceptSession(long sessionId, String accepter, int localPort) {
        VoiceSession session = activeSessions.get(sessionId);

        if (session == null) {
            log.warn("Cannot accept: Session {} not found", sessionId);
            return null;
        }

        if (!session.target().equals(accepter)) {
            log.warn("Cannot accept: User {} is not the target of session {}", accepter, sessionId);
            return null;
        }

        if (!"RINGING".equals(session.state())) {
            log.warn("Cannot accept: Session {} is in state {}, not RINGING", sessionId, session.state());
            return null;
        }

        session.setState("CONNECTED");
        session.updateTargetPort(localPort);
        log.info("Voice session {} accepted by {}. Target now has port {}", sessionId, accepter, localPort);
        return session;
    }

    /**
     * Rejects an incoming call.
     */
    public synchronized boolean rejectSession(long sessionId, String rejector) {
        VoiceSession session = activeSessions.get(sessionId);

        if (session == null) {
            log.warn("Cannot reject: Session {} not found", sessionId);
            return false;
        }

        if (!session.target().equals(rejector)) {
            log.warn("Cannot reject: User {} is not the target of session {}", rejector, sessionId);
            return false;
        }

        session.setState("REJECTED");
        log.info("Voice session {} rejected by {}", sessionId, rejector);
        // Optionally remove immediately, or let it expire
        activeSessions.remove(sessionId);
        return true;
    }

    /**
     * Shuts down the voice session manager.
     */
    public void shutdown() {
        sessionCleanupExecutor.shutdownNow();
        activeSessions.clear();
        log.info("Voice session manager shut down");
    }

    /**
     * Cleans up expired sessions (inactive for too long).
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        var expiredSessions = activeSessions.values().stream()
                .filter(s -> now - s.lastActivityTime() > SESSION_TIMEOUT_MS)
                .toList();

        for (VoiceSession session : expiredSessions) {
            activeSessions.remove(session.sessionId());
            log.info("Voice session {} expired due to inactivity", session.sessionId());
        }
    }

    /**
     * Represents an active voice session between two peers.
     * States: RINGING (waiting for acceptance) -> CONNECTED (both peers connected)
     * -> ENDED
     */
    public static class VoiceSession {
        private final long sessionId;
        private final String initiator;
        private final String target;
        private volatile String state; // RINGING, CONNECTED, REJECTED, ENDED
        private final String initiatorIp;
        private final int initiatorPort;
        private volatile String targetIp;
        private volatile int targetPort;
        private final long createdAt;
        private volatile long acceptedAt;
        private volatile long lastActivityTime;

        // WebRTC SDP (Session Description Protocol) for offer/answer exchange
        private volatile String initiatorSdpOffer; // Offer created by initiator
        private volatile String targetSdpAnswer; // Answer created by target
        private volatile boolean initiatorOfferReady = false;
        private volatile boolean targetAnswerReady = false;

        public VoiceSession(long sessionId, String initiator, String target,
                String initiatorIp, int initiatorPort,
                String targetIp, int targetPort, long createdAt) {
            this.sessionId = sessionId;
            this.initiator = initiator;
            this.target = target;
            this.state = "RINGING"; // Start in RINGING state
            this.initiatorIp = initiatorIp;
            this.initiatorPort = initiatorPort;
            this.targetIp = targetIp;
            this.targetPort = targetPort;
            this.createdAt = createdAt;
            this.acceptedAt = 0;
            this.lastActivityTime = createdAt;
        }

        public long sessionId() {
            return sessionId;
        }

        public String initiator() {
            return initiator;
        }

        public String target() {
            return target;
        }

        public String initiatorIp() {
            return initiatorIp;
        }

        public int initiatorPort() {
            return initiatorPort;
        }

        public String targetIp() {
            return targetIp;
        }

        public int targetPort() {
            return targetPort;
        }

        public long createdAt() {
            return createdAt;
        }

        public String state() {
            return state;
        }

        public long acceptedAt() {
            return acceptedAt;
        }

        public void setState(String newState) {
            this.state = newState;
            if ("CONNECTED".equals(newState)) {
                this.acceptedAt = System.currentTimeMillis();
            }
        }

        public void updateTargetPort(int newPort) {
            this.targetPort = newPort;
        }

        public long lastActivityTime() {
            return lastActivityTime;
        }

        public void updateLastActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }

        public long getDurationMs() {
            return System.currentTimeMillis() - createdAt;
        }

        // WebRTC SDP accessors
        public void setInitiatorSdpOffer(String sdpOffer) {
            this.initiatorSdpOffer = sdpOffer;
            this.initiatorOfferReady = true;
        }

        public String getInitiatorSdpOffer() {
            return initiatorSdpOffer;
        }

        public boolean isInitiatorOfferReady() {
            return initiatorOfferReady;
        }

        public void setTargetSdpAnswer(String sdpAnswer) {
            this.targetSdpAnswer = sdpAnswer;
            this.targetAnswerReady = true;
        }

        public String getTargetSdpAnswer() {
            return targetSdpAnswer;
        }

        public boolean isTargetAnswerReady() {
            return targetAnswerReady;
        }
    }
}
