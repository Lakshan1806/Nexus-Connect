package com.nexusconnect.servicebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Allowed origin patterns for CORS. Supports wildcard syntax accepted by
     * {@link org.springframework.web.cors.CorsConfiguration#setAllowedOriginPatterns(List)}
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://192.168.*.*:*",
            "http://10.*.*.*:*",
            "http://172.*.*.*:*",
            "https://YOUR-TUNNEL-URL.example.com"
    ));

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
