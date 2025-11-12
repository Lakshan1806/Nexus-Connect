package com.nexusconnect.servicebackend.web.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SignInRequest(
        @Email(message = "email must be valid") @NotBlank(message = "email is required") String email,
        @NotBlank(message = "password is required") String password
) {}
