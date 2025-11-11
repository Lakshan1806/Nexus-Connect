package com.nexusconnect.servicebackend.config;

import com.nexusconnect.servicebackend.whiteboard.WhiteboardSessionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for shared whiteboard feature.
 * Registers the WhiteboardSessionManager as a singleton bean.
 */
@Configuration
public class WhiteboardConfig {

    @Bean
    public WhiteboardSessionManager whiteboardSessionManager() {
        return new WhiteboardSessionManager();
    }
}
