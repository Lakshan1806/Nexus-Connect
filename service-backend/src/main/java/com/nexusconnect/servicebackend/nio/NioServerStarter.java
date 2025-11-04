package com.nexusconnect.servicebackend.nio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class NioServerStarter {
    private final int nioPort;

    public NioServerStarter(@Value("${nexus.nio.port:8081}") int nioPort) {
        this.nioPort = nioPort;
    }

    @Bean(destroyMethod = "stop")
    public NioChatServer nioChatServer() throws IOException {
        NioChatServer server = new NioChatServer(nioPort);
        server.start();
        return server;
    }
}
