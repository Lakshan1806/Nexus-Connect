package com.nexusconnect.servicebackend.webrtc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusconnect.servicebackend.voice.VoiceSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebRTC Signaling Server using WebSocket
 * 
 * This handler implements a persistent WebSocket connection for instant
 * signaling
 * in a WebRTC P2P voice chat application.
 * 
 * Key Features:
 * - Stateful connection management using java.nio WebSocket sessions
 * - Thread-safe user session registry (ConcurrentHashMap)
 * - Real-time forwarding of WebRTC offer/answer/ICE candidates
 * - Demonstrates multithreading and persistent connection handling
 * 
 * Network Programming Concepts:
 * - WebSocket protocol for bidirectional communication
 * - Stateful server managing multiple concurrent connections
 * - Message routing based on destination user
 */
@Component
public class WebRTCSignalingHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(WebRTCSignalingHandler.class);

    // Thread-safe map of username -> WebSocketSession
    private final ConcurrentHashMap<String, WebSocketSession> userSessions = new ConcurrentHashMap<>();

    // JSON parser for signaling messages
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Session manager to track voice sessions
    private final VoiceSessionManager sessionManager;

    public WebRTCSignalingHandler(VoiceSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Called when a new WebSocket connection is established.
     * Registers the user in our session map for message routing.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = extractUsername(session);

        if (username == null || username.isBlank()) {
            log.warn("WebSocket connection rejected: missing username parameter");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Username required"));
            return;
        }

        // Check if user already has a session (replace it)
        WebSocketSession oldSession = userSessions.put(username, session);
        if (oldSession != null && oldSession.isOpen()) {
            log.info("Replacing existing signaling session for user: {}", username);
            try {
                oldSession.close(CloseStatus.NORMAL.withReason("New connection established"));
            } catch (IOException e) {
                log.warn("Error closing old session: {}", e.getMessage());
            }
        }

        // Store username in session attributes
        session.getAttributes().put("username", username);

        log.info("✅ WebRTC Signaling WebSocket connected: user={}, sessionId={}, total_users={}",
                username, session.getId(), userSessions.size());

        // Send confirmation message
        sendMessage(session, new SignalingMessage(
                "system",
                username,
                "connected",
                Map.of("message", "Connected to signaling server"),
                null));
    }

    /**
     * Called when a WebSocket connection is closed.
     * Removes the user from our session registry.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String username = (String) session.getAttributes().get("username");

        if (username != null) {
            userSessions.remove(username);
            log.info("❌ WebRTC Signaling WebSocket disconnected: user={}, status={}, remaining_users={}",
                    username, status, userSessions.size());

            // Optionally terminate any active sessions for this user
            var activeSessions = sessionManager.getUserSessions(username);
            for (var voiceSession : activeSessions) {
                sessionManager.terminateSession(voiceSession.sessionId());
                log.info("Auto-terminated session {} due to signaling disconnect", voiceSession.sessionId());

                // Notify the other user
                String otherUser = voiceSession.initiator().equals(username)
                        ? voiceSession.target()
                        : voiceSession.initiator();

                WebSocketSession otherSession = userSessions.get(otherUser);
                if (otherSession != null && otherSession.isOpen()) {
                    sendMessage(otherSession, new SignalingMessage(
                            "system",
                            otherUser,
                            "peer-disconnected",
                            Map.of("peer", username, "sessionId", voiceSession.sessionId()),
                            null));
                }
            }
        }
    }

    /**
     * Handles incoming text messages (signaling messages).
     * Routes messages to the appropriate target user.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String username = (String) session.getAttributes().get("username");

        if (username == null) {
            log.warn("Received message from unauthenticated session: {}", session.getId());
            return;
        }

        try {
            // Parse the signaling message
            SignalingMessage signalingMsg = objectMapper.readValue(message.getPayload(), SignalingMessage.class);

            log.info("📨 Signaling message from {}: type={}, to={}",
                    username, signalingMsg.type(), signalingMsg.to());

            // Handle different message types
            switch (signalingMsg.type()) {
                case "offer":
                    handleOffer(username, signalingMsg);
                    break;
                case "answer":
                    handleAnswer(username, signalingMsg);
                    break;
                case "ice-candidate":
                    handleIceCandidate(username, signalingMsg);
                    break;
                case "call-initiate":
                    handleCallInitiate(username, signalingMsg);
                    break;
                case "call-accept":
                    handleCallAccept(username, signalingMsg);
                    break;
                case "call-reject":
                    handleCallReject(username, signalingMsg);
                    break;
                case "call-end":
                    handleCallEnd(username, signalingMsg);
                    break;
                default:
                    log.warn("Unknown message type: {} from user: {}", signalingMsg.type(), username);
            }

        } catch (Exception e) {
            log.error("Error handling signaling message from {}: {}", username, e.getMessage(), e);
            sendError(session, "Failed to process message: " + e.getMessage());
        }
    }

    /**
     * Handles WebRTC offer from initiator.
     * Creates a session and forwards the offer to the target user.
     */
    private void handleOffer(String from, SignalingMessage msg) {
        String target = msg.to();

        if (target == null || target.isBlank()) {
            log.warn("Offer from {} has no target", from);
            return;
        }

        // Create or update voice session
        Long sessionId = (Long) msg.data().get("sessionId");
        if (sessionId == null) {
            // Create new session
            sessionId = sessionManager.initiateSession(from, target, "0.0.0.0", 0, "0.0.0.0", 0);
            log.info("Created new voice session {} for offer from {} to {}", sessionId, from, target);
        }

        // Store the offer in the session
        var session = sessionManager.getSession(sessionId);
        if (session != null && msg.data().get("sdp") != null) {
            session.setInitiatorSdpOffer((String) msg.data().get("sdp"));
        }

        // Forward offer to target user
        WebSocketSession targetSession = userSessions.get(target);
        if (targetSession != null && targetSession.isOpen()) {
            Map<String, Object> data = new java.util.HashMap<>(msg.data());
            data.put("sessionId", sessionId);

            SignalingMessage forwardMsg = new SignalingMessage(
                    from,
                    target,
                    "offer",
                    data,
                    null);

            sendMessage(targetSession, forwardMsg);
            log.info("✅ Forwarded WebRTC offer from {} to {}, session={}", from, target, sessionId);
        } else {
            log.warn("Target user {} not connected to signaling server", target);

            // Send error back to sender
            WebSocketSession senderSession = userSessions.get(from);
            if (senderSession != null && senderSession.isOpen()) {
                sendError(senderSession, "Target user " + target + " is not online");
            }
        }
    }

    /**
     * Handles WebRTC answer from target.
     * Forwards the answer back to the initiator.
     */
    private void handleAnswer(String from, SignalingMessage msg) {
        String target = msg.to();
        Long sessionId = (Long) msg.data().get("sessionId");

        if (target == null || sessionId == null) {
            log.warn("Answer from {} missing target or sessionId", from);
            return;
        }

        // Store the answer in the session
        var session = sessionManager.getSession(sessionId);
        if (session != null && msg.data().get("sdp") != null) {
            session.setTargetSdpAnswer((String) msg.data().get("sdp"));
            session.setState("CONNECTED");
        }

        // Forward answer to initiator
        WebSocketSession targetSession = userSessions.get(target);
        if (targetSession != null && targetSession.isOpen()) {
            SignalingMessage forwardMsg = new SignalingMessage(
                    from,
                    target,
                    "answer",
                    msg.data(),
                    null);

            sendMessage(targetSession, forwardMsg);
            log.info("✅ Forwarded WebRTC answer from {} to {}, session={}", from, target, sessionId);
        } else {
            log.warn("Initiator {} not connected for answer", target);
        }
    }

    /**
     * Handles ICE candidate exchange.
     * Forwards ICE candidates between peers for NAT traversal.
     */
    private void handleIceCandidate(String from, SignalingMessage msg) {
        String target = msg.to();

        if (target == null) {
            log.warn("ICE candidate from {} has no target", from);
            return;
        }

        // Forward ICE candidate to target
        WebSocketSession targetSession = userSessions.get(target);
        if (targetSession != null && targetSession.isOpen()) {
            SignalingMessage forwardMsg = new SignalingMessage(
                    from,
                    target,
                    "ice-candidate",
                    msg.data(),
                    null);

            sendMessage(targetSession, forwardMsg);
            log.debug("✅ Forwarded ICE candidate from {} to {}", from, target);
        }
    }

    /**
     * Handles call initiation request.
     */
    private void handleCallInitiate(String from, SignalingMessage msg) {
        String target = msg.to();

        if (target == null) {
            log.warn("Call initiate from {} has no target", from);
            return;
        }

        // Create voice session
        long sessionId = sessionManager.initiateSession(from, target, "0.0.0.0", 0, "0.0.0.0", 0);

        // Notify target about incoming call
        WebSocketSession targetSession = userSessions.get(target);
        if (targetSession != null && targetSession.isOpen()) {
            SignalingMessage callMsg = new SignalingMessage(
                    from,
                    target,
                    "incoming-call",
                    Map.of("sessionId", sessionId, "caller", from),
                    null);

            sendMessage(targetSession, callMsg);
            log.info("✅ Notified {} about incoming call from {}, session={}", target, from, sessionId);

            // Confirm to initiator
            WebSocketSession fromSession = userSessions.get(from);
            if (fromSession != null && fromSession.isOpen()) {
                sendMessage(fromSession, new SignalingMessage(
                        "system",
                        from,
                        "call-initiated",
                        Map.of("sessionId", sessionId, "target", target),
                        null));
            }
        } else {
            log.warn("Target user {} not available for call", target);

            WebSocketSession fromSession = userSessions.get(from);
            if (fromSession != null && fromSession.isOpen()) {
                sendError(fromSession, "User " + target + " is not available");
            }
        }
    }

    /**
     * Handles call acceptance.
     */
    private void handleCallAccept(String from, SignalingMessage msg) {
        Long sessionId = (Long) msg.data().get("sessionId");

        if (sessionId == null) {
            log.warn("Call accept from {} missing sessionId", from);
            return;
        }

        var session = sessionManager.getSession(sessionId);
        if (session != null) {
            session.setState("ACCEPTED");

            // Notify initiator
            String initiator = session.initiator();
            WebSocketSession initiatorSession = userSessions.get(initiator);
            if (initiatorSession != null && initiatorSession.isOpen()) {
                sendMessage(initiatorSession, new SignalingMessage(
                        from,
                        initiator,
                        "call-accepted",
                        Map.of("sessionId", sessionId, "accepter", from),
                        null));
                log.info("✅ Notified {} that {} accepted the call", initiator, from);
            }
        }
    }

    /**
     * Handles call rejection.
     */
    private void handleCallReject(String from, SignalingMessage msg) {
        Long sessionId = (Long) msg.data().get("sessionId");

        if (sessionId == null) {
            log.warn("Call reject from {} missing sessionId", from);
            return;
        }

        var session = sessionManager.getSession(sessionId);
        if (session != null) {
            sessionManager.rejectSession(sessionId, from);

            // Notify initiator
            String initiator = session.initiator();
            WebSocketSession initiatorSession = userSessions.get(initiator);
            if (initiatorSession != null && initiatorSession.isOpen()) {
                sendMessage(initiatorSession, new SignalingMessage(
                        from,
                        initiator,
                        "call-rejected",
                        Map.of("sessionId", sessionId, "rejecter", from),
                        null));
                log.info("✅ Notified {} that {} rejected the call", initiator, from);
            }
        }
    }

    /**
     * Handles call termination.
     */
    private void handleCallEnd(String from, SignalingMessage msg) {
        Long sessionId = (Long) msg.data().get("sessionId");

        if (sessionId == null) {
            log.warn("Call end from {} missing sessionId", from);
            return;
        }

        var session = sessionManager.getSession(sessionId);
        if (session != null) {
            // Notify the other peer
            String otherUser = session.initiator().equals(from)
                    ? session.target()
                    : session.initiator();

            WebSocketSession otherSession = userSessions.get(otherUser);
            if (otherSession != null && otherSession.isOpen()) {
                sendMessage(otherSession, new SignalingMessage(
                        from,
                        otherUser,
                        "call-ended",
                        Map.of("sessionId", sessionId, "endedBy", from),
                        null));
                log.info("✅ Notified {} that {} ended the call", otherUser, from);
            }

            // Terminate the session
            sessionManager.terminateSession(sessionId);
        }
    }

    /**
     * Sends a signaling message to a WebSocket session.
     */
    private void sendMessage(WebSocketSession session, SignalingMessage msg) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.error("Failed to send message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    /**
     * Sends an error message to a WebSocket session.
     */
    private void sendError(WebSocketSession session, String error) {
        String username = (String) session.getAttributes().get("username");
        sendMessage(session, new SignalingMessage(
                "system",
                username != null ? username : "unknown",
                "error",
                Map.of("message", error),
                null));
    }

    /**
     * Extracts username from WebSocket query parameters.
     */
    private String extractUsername(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "username".equals(kv[0])) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    /**
     * Gets the number of connected users.
     */
    public int getConnectedUserCount() {
        return userSessions.size();
    }

    /**
     * Checks if a user is connected to the signaling server.
     */
    public boolean isUserConnected(String username) {
        WebSocketSession session = userSessions.get(username);
        return session != null && session.isOpen();
    }

    /**
     * Signaling message format.
     */
    public record SignalingMessage(
            String from,
            String to,
            String type,
            Map<String, Object> data,
            String error) {
    }
}
