package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record MessageRequest(
        @NotBlank(message = "message text is required") String text
) {}
