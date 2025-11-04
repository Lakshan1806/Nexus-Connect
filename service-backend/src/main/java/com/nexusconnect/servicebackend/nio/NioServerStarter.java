package com.nexusconnect.servicebackend.nio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class NioServerStarter {
    @Value("${nexus.nio.port:8081}")
    private int nioPort;
    private NioChatServer server;

    @PostConstruct
    public void start() throws Exception {
        server = new NioChatServer(nioPort);
        server.start();
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.stop();
    }

}

