package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.whiteboard.WhiteboardSessionManager;
import com.nexusconnect.servicebackend.whiteboard.WhiteboardSessionManager.DrawingCommand;
import com.nexusconnect.servicebackend.whiteboard.WhiteboardSessionManager.WhiteboardSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API endpoints for shared whiteboard feature.
 * Handles session creation, drawing commands, and synchronization.
 */
@RestController
@RequestMapping("/api/whiteboard")
public class WhiteboardController {
    private final WhiteboardSessionManager whiteboardSessionManager;

    public WhiteboardController(WhiteboardSessionManager whiteboardSessionManager) {
        this.whiteboardSessionManager = whiteboardSessionManager;
    }

    /**
     * Create a new whiteboard session between two users.
     */
    @PostMapping("/create")
    public ResponseEntity<?> createSession(@RequestBody Map<String, String> request) {
        String initiator = request.get("initiator");
        String participant = request.get("participant");

        if (initiator == null || participant == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing initiator or participant"));
        }

        long sessionId = whiteboardSessionManager.createSession(initiator, participant);
        
        if (sessionId < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to create session"));
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "initiator", initiator,
                "participant", participant
        ));
    }

    /**
     * Send a drawing command to a whiteboard session.
     */
    @PostMapping("/draw")
    public ResponseEntity<?> sendDrawingCommand(@RequestBody Map<String, Object> request) {
        try {
            Long sessionId = getLong(request, "sessionId");
            String username = (String) request.get("username");
            String type = (String) request.get("type");

            if (sessionId == null || username == null || type == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required fields"));
            }

            // Verify user is part of the session
            if (!whiteboardSessionManager.isUserInSession(sessionId, username)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "User not in this session"));
            }

            DrawingCommand command;
            
            if ("clear".equals(type)) {
                command = DrawingCommand.clear(username);
                whiteboardSessionManager.clearSession(sessionId);
            } else if ("draw".equals(type)) {
                double x1 = getDouble(request, "x1");
                double y1 = getDouble(request, "y1");
                double x2 = getDouble(request, "x2");
                double y2 = getDouble(request, "y2");
                String color = (String) request.getOrDefault("color", "#000000");
                double thickness = getDouble(request, "thickness", 2.0);

                command = DrawingCommand.draw(username, x1, y1, x2, y2, color, thickness);
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Unknown command type"));
            }

            whiteboardSessionManager.addDrawingCommand(sessionId, command);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "command", Map.of(
                            "type", command.type(),
                            "username", command.username(),
                            "timestamp", command.timestamp()
                    )
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid request: " + e.getMessage()));
        }
    }

    /**
     * Get all drawing commands for a session (for synchronization).
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionCommands(
            @PathVariable Long sessionId,
            @RequestParam String username) {
        
        if (!whiteboardSessionManager.isUserInSession(sessionId, username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User not in this session"));
        }

        List<DrawingCommand> commands = whiteboardSessionManager.getSessionCommands(sessionId);
        
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "commands", commands,
                "count", commands.size()
        ));
    }

    /**
     * Close a whiteboard session.
     */
    @PostMapping("/close")
    public ResponseEntity<?> closeSession(@RequestBody Map<String, Object> request) {
        Long sessionId = getLong(request, "sessionId");
        String username = (String) request.get("username");

        if (sessionId == null || username == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Missing sessionId or username"));
        }

        if (!whiteboardSessionManager.isUserInSession(sessionId, username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User not in this session"));
        }

        boolean closed = whiteboardSessionManager.closeSession(sessionId);
        
        if (closed) {
            return ResponseEntity.ok(Map.of("success", true));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get session info.
     */
    @GetMapping("/session/{sessionId}/info")
    public ResponseEntity<?> getSessionInfo(@PathVariable Long sessionId, @RequestParam String username) {
        if (!whiteboardSessionManager.isUserInSession(sessionId, username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User not in this session"));
        }

        WhiteboardSession session = whiteboardSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        String otherUser = whiteboardSessionManager.getOtherUser(sessionId, username);

        return ResponseEntity.ok(Map.of(
                "sessionId", session.sessionId(),
                "initiator", session.initiator(),
                "participant", session.participant(),
                "otherUser", otherUser,
                "createdAt", session.createdAt(),
                "commandCount", session.getCommandCount()
        ));
    }

    /**
     * Get pending whiteboard sessions for a user (for notifications).
     */
    @GetMapping("/pending/{username}")
    public ResponseEntity<?> getPendingSessions(@PathVariable String username) {
        List<WhiteboardSession> userSessions = whiteboardSessionManager.getUserSessions(username);
        
        // Return all active sessions involving this user
        List<Map<String, Object>> pendingSessions = userSessions.stream()
                .map(session -> Map.<String, Object>of(
                        "sessionId", session.sessionId(),
                        "initiator", session.initiator(),
                        "participant", session.participant(),
                        "otherUser", session.initiator().equals(username) ? session.participant() : session.initiator(),
                        "createdAt", session.createdAt()
                ))
                .toList();
        
        return ResponseEntity.ok(pendingSessions);
    }

    // Helper methods
    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private double getDouble(Map<String, Object> map, String key) {
        return getDouble(map, key, 0.0);
    }

    private double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return defaultValue;
    }
}
