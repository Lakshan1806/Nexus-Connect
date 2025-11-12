package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.Positive;

public record NioConnectRequest(
        @Positive(message = "fileTcp must be positive")
        Integer fileTcp,
        @Positive(message = "voiceUdp must be positive")
        Integer voiceUdp,
        String ipOverride
) {}
