package com.nexusconnect.servicebackend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * WebSocket Handler for Voice Audio Streaming
 * 
 * This handler processes WebSocket connections on the /ws/voice endpoint.
 * It receives raw audio data as binary messages from React clients and
 * delegates the relay logic to VoiceRelayManager.
 * 
 * The handler extracts the username from the WebSocket query parameters
 * and uses it to register/unregister sessions and relay audio.
 */
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private final VoiceRelayManager voiceRelayManager;

    public VoiceWebSocketHandler(VoiceRelayManager voiceRelayManager) {
        this.voiceRelayManager = voiceRelayManager;
    }

    /**
     * Called when a new WebSocket connection is established.
     * Extracts the username from query parameters and registers the session.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = extractUsername(session);

        if (username == null || username.isBlank()) {
            log.warn("WebSocket connection rejected: missing username parameter");
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Username required"));
            return;
        }

        log.info("Voice WebSocket connected: user={}, sessionId={}", username, session.getId());

        // Register this session with the relay manager
        voiceRelayManager.registerSession(username, session);

        // Store username in session attributes for later use
        session.getAttributes().put("username", username);
    }

    /**
     * Called when a WebSocket connection is closed.
     * Unregisters the session from the relay manager.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String username = (String) session.getAttributes().get("username");

        if (username != null) {
            log.info("Voice WebSocket disconnected: user={}, sessionId={}, status={}",
                    username, session.getId(), status);
            voiceRelayManager.unregisterSession(username);
        }
    }

    /**
     * Handles incoming binary messages (raw audio data).
     * Extracts the ByteBuffer and passes it to the relay manager for forwarding.
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String username = (String) session.getAttributes().get("username");

        if (username == null) {
            log.warn("Received binary message from unauthenticated session: {}", session.getId());
            return;
        }

        // Get the audio data as ByteBuffer
        ByteBuffer audioData = message.getPayload();

        if (audioData.remaining() == 0) {
            log.trace("Received empty audio buffer from user: {}", username);
            return;
        }

        log.trace("Received {} bytes of audio from user: {}", audioData.remaining(), username);

        // Delegate to relay manager for forwarding
        voiceRelayManager.relayAudioData(username, audioData);
    }

    /**
     * Handles incoming text messages (optional, for control messages).
     * Currently logs a warning as we expect only binary audio data.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String username = (String) session.getAttributes().get("username");
        log.warn("Received unexpected text message from user {}: {}", username, message.getPayload());
    }

    /**
     * Handles transport errors.
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String username = (String) session.getAttributes().get("username");
        log.error("WebSocket transport error for user {}: {}", username, exception.getMessage());

        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * Extracts username from WebSocket query parameters.
     * Expected URL format: ws://host:port/ws/voice?username=john
     * 
     * @param session The WebSocket session
     * @return The username or null if not found
     */
    private String extractUsername(WebSocketSession session) {
        try {
            String query = session.getUri().getQuery();
            if (query == null || query.isBlank()) {
                return null;
            }

            // Parse query string manually (simple implementation)
            Map<String, String> params = parseQueryString(query);
            return params.get("username");
        } catch (Exception e) {
            log.error("Error extracting username from WebSocket URI: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Simple query string parser.
     * Parses "key1=value1&key2=value2" format.
     */
    private Map<String, String> parseQueryString(String query) {
        Map<String, String> params = new java.util.HashMap<>();

        if (query == null || query.isBlank()) {
            return params;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                String key = java.net.URLDecoder.decode(keyValue[0], java.nio.charset.StandardCharsets.UTF_8);
                String value = java.net.URLDecoder.decode(keyValue[1], java.nio.charset.StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }

        return params;
    }

    /**
     * Configure to handle partial messages (useful for large audio chunks).
     */
    @Override
    public boolean supportsPartialMessages() {
        return false; // Set to true if you want to handle fragmented messages
    }
}
