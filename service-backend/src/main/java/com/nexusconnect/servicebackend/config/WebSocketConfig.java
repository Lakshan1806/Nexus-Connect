package com.nexusconnect.servicebackend.config;

import com.nexusconnect.servicebackend.voice.VoiceWebSocketHandler;
import com.nexusconnect.servicebackend.webrtc.WebRTCSignalingHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket Configuration for Voice Streaming and WebRTC Signaling
 * 
 * Configures WebSocket endpoints:
 * 1. /ws/voice - Binary WebSocket for server-relayed voice audio (old approach)
 * 2. /ws/signaling - Text WebSocket for WebRTC signaling (new P2P approach)
 * 
 * Clients connect with:
 * - ws://host:port/ws/voice?username=john (server-relay audio)
 * - ws://host:port/ws/signaling?username=john (WebRTC signaling)
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler voiceWebSocketHandler;
    private final WebRTCSignalingHandler webRTCSignalingHandler;

    public WebSocketConfig(VoiceWebSocketHandler voiceWebSocketHandler,
            WebRTCSignalingHandler webRTCSignalingHandler) {
        this.voiceWebSocketHandler = voiceWebSocketHandler;
        this.webRTCSignalingHandler = webRTCSignalingHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Old server-relay audio endpoint (kept for backward compatibility)
        registry.addHandler(voiceWebSocketHandler, "/ws/voice")
                .setAllowedOrigins("*"); // Configure CORS - restrict in production!

        // New WebRTC signaling endpoint (recommended for P2P voice chat)
        registry.addHandler(webRTCSignalingHandler, "/ws/signaling")
                .setAllowedOrigins("*"); // Configure CORS - restrict in production!
    }
}
