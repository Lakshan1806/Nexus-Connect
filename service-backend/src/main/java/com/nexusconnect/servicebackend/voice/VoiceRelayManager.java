package com.nexusconnect.servicebackend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Java Voice Relay Manager - Server-Relay Model
 * 
 * This class handles the server-side relay of voice audio streams between
 * clients.
 * Instead of P2P WebRTC, clients send raw audio data via WebSocket to the
 * server,
 * and the server forwards it to the appropriate peer in real-time.
 * 
 * Key responsibilities:
 * - Track WebSocket sessions for each connected user
 * - Use VoiceSessionManager to determine active voice pairs
 * - Relay audio ByteBuffers from sender to receiver in a non-blocking,
 * thread-safe manner
 */
@Component
public class VoiceRelayManager {
    private static final Logger log = LoggerFactory.getLogger(VoiceRelayManager.class);

    // Maps username -> WebSocketSession for voice streaming
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // Reference to the voice session manager to determine who is talking to whom
    private final VoiceSessionManager voiceSessionManager;

    // Thread pool for non-blocking audio relay operations
    private final ExecutorService relayExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            r -> {
                Thread t = new Thread(r, "voice-relay-worker");
                t.setDaemon(true);
                return t;
            });

    public VoiceRelayManager(VoiceSessionManager voiceSessionManager) {
        this.voiceSessionManager = voiceSessionManager;
    }

    /**
     * Registers a new WebSocket session for a user.
     * Called when a client connects to the /ws/voice endpoint.
     * 
     * @param username The username of the connected client
     * @param session  The WebSocket session
     */
    public void registerSession(String username, WebSocketSession session) {
        if (username == null || session == null) {
            log.warn("Cannot register session: username or session is null");
            return;
        }

        WebSocketSession oldSession = activeSessions.put(username, session);
        if (oldSession != null && oldSession.isOpen()) {
            log.info("Replacing existing WebSocket session for user: {}", username);
            try {
                oldSession.close();
            } catch (IOException e) {
                log.warn("Error closing old session for user {}: {}", username, e.getMessage());
            }
        }

        log.info("Voice WebSocket session registered for user: {} (session ID: {})",
                username, session.getId());
    }

    /**
     * Unregisters a WebSocket session for a user.
     * Called when a client disconnects from the /ws/voice endpoint.
     * 
     * @param username The username of the disconnected client
     */
    public void unregisterSession(String username) {
        if (username == null) {
            return;
        }

        WebSocketSession removed = activeSessions.remove(username);
        if (removed != null) {
            log.info("Voice WebSocket session unregistered for user: {} (session ID: {})",
                    username, removed.getId());

            // Optionally terminate any active voice sessions for this user
            var userSessions = voiceSessionManager.getUserSessions(username);
            for (var voiceSession : userSessions) {
                voiceSessionManager.terminateSession(voiceSession.sessionId());
                log.info("Auto-terminated voice session {} due to WebSocket disconnect",
                        voiceSession.sessionId());
            }
        }
    }

    /**
     * Handles incoming audio data from a client and relays it to the appropriate
     * peer.
     * This is the core relay logic - it determines who the sender is talking to
     * and forwards the audio buffer to that peer's WebSocket session.
     * 
     * @param senderUsername The username of the client sending audio
     * @param audioData      The raw audio data as a ByteBuffer
     */
    public void relayAudioData(String senderUsername, ByteBuffer audioData) {
        if (senderUsername == null || audioData == null || audioData.remaining() == 0) {
            return;
        }

        // Submit relay task to thread pool for non-blocking processing
        relayExecutor.submit(() -> {
            try {
                // Find active voice session(s) for this sender
                var activeSessions = voiceSessionManager.getUserSessions(senderUsername);

                if (activeSessions.isEmpty()) {
                    log.trace("No active voice sessions for user: {}", senderUsername);
                    return;
                }

                // For each active session, determine the peer and relay audio
                for (var voiceSession : activeSessions) {
                    // Only relay if session is CONNECTED
                    if (!"CONNECTED".equals(voiceSession.state())) {
                        continue;
                    }

                    // Determine who the peer is (the other person in the call)
                    String peerUsername;
                    if (voiceSession.initiator().equals(senderUsername)) {
                        peerUsername = voiceSession.target();
                    } else if (voiceSession.target().equals(senderUsername)) {
                        peerUsername = voiceSession.initiator();
                    } else {
                        // Sender is not part of this session (shouldn't happen)
                        continue;
                    }

                    // Get peer's WebSocket session
                    WebSocketSession peerSession = this.activeSessions.get(peerUsername);
                    if (peerSession == null || !peerSession.isOpen()) {
                        log.trace("Peer {} not connected or session closed", peerUsername);
                        continue;
                    }

                    // Forward the audio data to the peer
                    // Create a new ByteBuffer with the same data (defensive copy)
                    ByteBuffer relayBuffer = ByteBuffer.allocate(audioData.remaining());
                    audioData.mark();
                    relayBuffer.put(audioData);
                    audioData.reset();
                    relayBuffer.flip();

                    // Send as binary WebSocket message
                    BinaryMessage binaryMessage = new BinaryMessage(relayBuffer);

                    synchronized (peerSession) {
                        if (peerSession.isOpen()) {
                            peerSession.sendMessage(binaryMessage);
                            log.trace("Relayed {} bytes of audio from {} to {}",
                                    relayBuffer.remaining(), senderUsername, peerUsername);

                            // Update session activity
                            voiceSession.updateLastActivity();
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Error relaying audio data from {}: {}", senderUsername, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in relay for {}: {}", senderUsername, e.getMessage(), e);
            }
        });
    }

    /**
     * Checks if a user has an active WebSocket connection.
     * 
     * @param username The username to check
     * @return true if the user has an open WebSocket session
     */
    public boolean isUserConnected(String username) {
        WebSocketSession session = activeSessions.get(username);
        return session != null && session.isOpen();
    }

    /**
     * Gets the number of active WebSocket sessions.
     * 
     * @return The count of active sessions
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Shuts down the relay manager and closes all connections.
     */
    public void shutdown() {
        log.info("Shutting down VoiceRelayManager...");

        // Close all WebSocket sessions
        activeSessions.forEach((username, session) -> {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (IOException e) {
                log.warn("Error closing session for {}: {}", username, e.getMessage());
            }
        });

        activeSessions.clear();
        relayExecutor.shutdown();
        log.info("VoiceRelayManager shut down complete");
    }
}
