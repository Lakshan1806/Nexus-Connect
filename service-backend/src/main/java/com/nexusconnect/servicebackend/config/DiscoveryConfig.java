package com.nexusconnect.servicebackend.config;

import com.nexusconnect.servicebackend.discovery.UdpDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class DiscoveryConfig {
    private static final Logger log = LoggerFactory.getLogger(DiscoveryConfig.class);

    @Bean(destroyMethod = "stop")
    public UdpDiscoveryService udpDiscoveryService() {
        UdpDiscoveryService service = new UdpDiscoveryService();
        try {
            service.start();
        } catch (IOException e) {
            log.error("Failed to start UDP Discovery Service", e);
            throw new RuntimeException("UDP Discovery Service initialization failed", e);
        }
        return service;
    }
}