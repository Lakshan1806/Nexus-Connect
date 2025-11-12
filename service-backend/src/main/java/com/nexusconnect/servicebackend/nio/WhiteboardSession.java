package com.nexusconnect.servicebackend.nio;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a whiteboard session between two users in the NIO server.
 * Stores drawing commands and manages session state.
 */
public class WhiteboardSession {
    private final long sessionId;
    private final String user1;
    private final String user2;
    private final long createdAt;
    private final AtomicLong lastActivity;
    private final ConcurrentLinkedQueue<DrawCommand> commands;

    public WhiteboardSession(long sessionId, String user1, String user2) {
        this.sessionId = sessionId;
        this.user1 = user1;
        this.user2 = user2;
        this.createdAt = System.currentTimeMillis();
        this.lastActivity = new AtomicLong(createdAt);
        this.commands = new ConcurrentLinkedQueue<>();
    }

    public void addCommand(DrawCommand command) {
        commands.offer(command);
        updateActivity();
    }

    public void clearCommands() {
        commands.clear();
        updateActivity();
    }

    public ConcurrentLinkedQueue<DrawCommand> getCommands() {
        return commands;
    }

    public void updateActivity() {
        lastActivity.set(System.currentTimeMillis());
    }

    public boolean isExpired(long timeoutMs) {
        return System.currentTimeMillis() - lastActivity.get() > timeoutMs;
    }

    public boolean hasUser(String username) {
        return user1.equals(username) || user2.equals(username);
    }

    public String getOtherUser(String username) {
        if (user1.equals(username)) return user2;
        if (user2.equals(username)) return user1;
        return null;
    }

    public long getSessionId() {
        return sessionId;
    }

    public String getUser1() {
        return user1;
    }

    public String getUser2() {
        return user2;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getCommandCount() {
        return commands.size();
    }

    /**
     * Drawing command record
     */
    public record DrawCommand(
            String type,      // "draw" or "clear"
            String username,
            double x1,
            double y1,
            double x2,
            double y2,
            String color,
            double thickness,
            long timestamp
    ) {
        public static DrawCommand draw(String username, double x1, double y1, double x2, double y2, 
                                      String color, double thickness) {
            return new DrawCommand("draw", username, x1, y1, x2, y2, color, thickness, 
                                 System.currentTimeMillis());
        }

        public static DrawCommand clear(String username) {
            return new DrawCommand("clear", username, 0, 0, 0, 0, "", 0, 
                                 System.currentTimeMillis());
        }
    }
}
