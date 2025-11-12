package com.nexusconnect.servicebackend.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignUpRequest(
        @NotBlank(message = "name is required")
        @Size(min = 3, max = 40, message = "name must be between 3 and 40 characters")
        String name,
        @Email(message = "email must be valid")
        @NotBlank(message = "email is required")
        String email,
        @NotBlank(message = "password is required")
        @Size(min = 6, max = 100, message = "password must be at least 6 characters long")
        String password
) {}
