package com.nexusconnect.servicebackend.nio;

import com.nexusconnect.servicebackend.user.UserCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class NioServerStarter {
    private final int nioPort;
    private final UserCredentialService credentialService;

    public NioServerStarter(@Value("${nexus.nio.port:8081}") int nioPort,
                            UserCredentialService credentialService) {
        this.nioPort = nioPort;
        this.credentialService = credentialService;
    }

    @Bean(destroyMethod = "stop")
    public NioChatServer nioChatServer() throws IOException {
        NioChatServer server = new NioChatServer(nioPort, credentialService);
        server.start();
        return server;
    }
}
