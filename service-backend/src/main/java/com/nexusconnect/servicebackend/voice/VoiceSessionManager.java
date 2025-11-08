package com.nexusconnect.servicebackend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages P2P voice sessions between two clients.
 * Handles session lifecycle, peer details exchange, and session cleanup.
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
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
     * Shuts down the voice session manager.
     */
    public void shutdown() {
        sessionCleanupExecutor.shutdownNow();
        activeSessions.clear();
        log.info("Voice session manager shut down");
    }

    /**
     * Represents an active voice session between two peers.
     */
    public static class VoiceSession {
        private final long sessionId;
        private final String initiator;
        private final String target;
        private final String initiatorIp;
        private final int initiatorPort;
        private final String targetIp;
        private final int targetPort;
        private final long createdAt;
        private volatile long lastActivityTime;

        public VoiceSession(long sessionId, String initiator, String target,
                String initiatorIp, int initiatorPort,
                String targetIp, int targetPort, long createdAt) {
            this.sessionId = sessionId;
            this.initiator = initiator;
            this.target = target;
            this.initiatorIp = initiatorIp;
            this.initiatorPort = initiatorPort;
            this.targetIp = targetIp;
            this.targetPort = targetPort;
            this.createdAt = createdAt;
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

        public long lastActivityTime() {
            return lastActivityTime;
        }

        public void updateLastActivity() {
            this.lastActivityTime = System.currentTimeMillis();
        }

        public long getDurationMs() {
            return System.currentTimeMillis() - createdAt;
        }
    }
}
