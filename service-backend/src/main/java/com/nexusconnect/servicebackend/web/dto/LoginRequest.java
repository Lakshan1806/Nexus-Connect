package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "username is required") String user,
        @NotBlank(message = "password is required") String pass,
        Integer fileTcp,
        Integer voiceUdp,
        String ipOverride
) {}
