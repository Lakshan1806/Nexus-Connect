package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(@NotBlank(message = "username is required") String user) {}
