package com.nexusconnect.servicebackend.config;

import com.nexusconnect.servicebackend.voice.VoiceSessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for voice module.
 * Registers the VoiceSessionManager as a singleton bean.
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
@Configuration
public class VoiceConfig {

    @Bean
    public VoiceSessionManager voiceSessionManager() {
        return new VoiceSessionManager();
    }
}
