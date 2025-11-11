package com.nexusconnect.servicebackend.whiteboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Manages shared whiteboard sessions between two peers.
 * Handles session lifecycle, drawing command routing, and synchronization.
 * Demonstrates real-time P2P data synchronization beyond voice/files.
 */
public class WhiteboardSessionManager {
    private static final Logger log = LoggerFactory.getLogger(WhiteboardSessionManager.class);
    private static final long SESSION_TIMEOUT_MS = 60 * 60 * 1000; // 1 hour

    // Map of session ID to WhiteboardSession
    private final ConcurrentHashMap<Long, WhiteboardSession> activeSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "whiteboard-session-cleanup");
        t.setDaemon(true);
        return t;
    });
    private long sessionIdCounter = System.currentTimeMillis();

    public WhiteboardSessionManager() {
        // Start periodic cleanup of timed-out sessions
        sessionCleanupExecutor.scheduleAtFixedRate(
                this::cleanupExpiredSessions,
                SESSION_TIMEOUT_MS,
                SESSION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new whiteboard session between two users.
     * If a session already exists between these users, returns the existing session ID.
     */
    public synchronized long createSession(String initiator, String participant) {
        if (initiator == null || participant == null || initiator.equals(participant)) {
            log.warn("Invalid whiteboard session parameters");
            return -1;
        }

        // Check if session already exists between these two users
        java.util.Optional<WhiteboardSession> existingSession = activeSessions.values().stream()
                .filter(s -> (s.initiator().equals(initiator) && s.participant().equals(participant)) ||
                             (s.initiator().equals(participant) && s.participant().equals(initiator)))
                .findFirst();

        if (existingSession.isPresent()) {
            long existingId = existingSession.get().sessionId();
            log.info("Reusing existing whiteboard session {} for {} <-> {}", 
                    existingId, initiator, participant);
            return existingId;
        }

        long sessionId = ++sessionIdCounter;
        WhiteboardSession session = new WhiteboardSession(
                sessionId,
                initiator,
                participant,
                System.currentTimeMillis());

        activeSessions.put(sessionId, session);
        log.info("Whiteboard session {} created: {} <-> {}", sessionId, initiator, participant);

        return sessionId;
    }

    /**
     * Gets an active whiteboard session by ID.
     */
    public WhiteboardSession getSession(long sessionId) {
        WhiteboardSession session = activeSessions.get(sessionId);
        if (session != null) {
            session.updateLastActivity();
        }
        return session;
    }

    /**
     * Adds a drawing command to the session history.
     */
    public boolean addDrawingCommand(long sessionId, DrawingCommand command) {
        WhiteboardSession session = activeSessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.addCommand(command);
        return true;
    }

    /**
     * Gets all drawing commands for a session (for new joiners to catch up).
     */
    public List<DrawingCommand> getSessionCommands(long sessionId) {
        WhiteboardSession session = activeSessions.get(sessionId);
        return session != null ? session.getCommands() : List.of();
    }

    /**
     * Clears the whiteboard (removes all drawing commands).
     */
    public boolean clearSession(long sessionId) {
        WhiteboardSession session = activeSessions.get(sessionId);
        if (session == null) {
            return false;
        }
        session.clearCommands();
        log.info("Whiteboard session {} cleared", sessionId);
        return true;
    }

    /**
     * Closes a whiteboard session.
     */
    public boolean closeSession(long sessionId) {
        WhiteboardSession session = activeSessions.remove(sessionId);
        if (session != null) {
            log.info("Whiteboard session {} closed: {} <-> {}",
                    sessionId, session.initiator(), session.participant());
            return true;
        }
        return false;
    }

    /**
     * Gets all sessions involving a specific user.
     */
    public List<WhiteboardSession> getUserSessions(String username) {
        return activeSessions.values().stream()
                .filter(s -> s.initiator().equals(username) || s.participant().equals(username))
                .toList();
    }

    /**
     * Checks if a user is part of a session.
     */
    public boolean isUserInSession(long sessionId, String username) {
        WhiteboardSession session = activeSessions.get(sessionId);
        return session != null &&
                (session.initiator().equals(username) || session.participant().equals(username));
    }

    /**
     * Gets the other user in the session.
     */
    public String getOtherUser(long sessionId, String currentUser) {
        WhiteboardSession session = activeSessions.get(sessionId);
        if (session == null) return null;
        
        if (session.initiator().equals(currentUser)) {
            return session.participant();
        } else if (session.participant().equals(currentUser)) {
            return session.initiator();
        }
        return null;
    }

    /**
     * Shuts down the whiteboard session manager.
     */
    public void shutdown() {
        sessionCleanupExecutor.shutdownNow();
        activeSessions.clear();
        log.info("Whiteboard session manager shut down");
    }

    /**
     * Cleans up expired sessions (inactive for too long).
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        var expiredSessions = activeSessions.values().stream()
                .filter(s -> now - s.lastActivityTime() > SESSION_TIMEOUT_MS)
                .toList();

        for (WhiteboardSession session : expiredSessions) {
            activeSessions.remove(session.sessionId());
            log.info("Whiteboard session {} expired due to inactivity", session.sessionId());
        }
    }

    /**
     * Represents an active whiteboard session between two users.
     */
    public static class WhiteboardSession {
        private final long sessionId;
        private final String initiator;
        private final String participant;
        private final long createdAt;
        private volatile long lastActivityTime;
        private final List<DrawingCommand> commands;

        public WhiteboardSession(long sessionId, String initiator, String participant, long createdAt) {
            this.sessionId = sessionId;
            this.initiator = initiator;
            this.participant = participant;
            this.createdAt = createdAt;
            this.lastActivityTime = createdAt;
            this.commands = new CopyOnWriteArrayList<>();
        }

        public long sessionId() {
            return sessionId;
        }

        public String initiator() {
            return initiator;
        }

        public String participant() {
            return participant;
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

        public void addCommand(DrawingCommand command) {
            commands.add(command);
            updateLastActivity();
        }

        public List<DrawingCommand> getCommands() {
            return new ArrayList<>(commands);
        }

        public void clearCommands() {
            commands.clear();
            updateLastActivity();
        }

        public int getCommandCount() {
            return commands.size();
        }
    }

    /**
     * Represents a drawing command (line, clear, etc.).
     */
    public record DrawingCommand(
            String type,        // "draw", "clear"
            String username,    // Who issued the command
            double x1,          // Start X coordinate
            double y1,          // Start Y coordinate
            double x2,          // End X coordinate
            double y2,          // End Y coordinate
            String color,       // Stroke color (hex)
            double thickness,   // Line thickness
            long timestamp      // When the command was issued
    ) {
        public static DrawingCommand draw(String username, double x1, double y1, 
                                         double x2, double y2, String color, double thickness) {
            return new DrawingCommand("draw", username, x1, y1, x2, y2, color, thickness, 
                                     System.currentTimeMillis());
        }

        public static DrawingCommand clear(String username) {
            return new DrawingCommand("clear", username, 0, 0, 0, 0, "", 0, 
                                     System.currentTimeMillis());
        }
    }
}
