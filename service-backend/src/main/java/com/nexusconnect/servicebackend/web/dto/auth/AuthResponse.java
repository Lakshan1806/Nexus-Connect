package com.nexusconnect.servicebackend.web.dto.auth;

public record AuthResponse(
        String token,
        UserProfileDto user
) {
    public static AuthResponse of(String token, UserProfileDto user) {
        return new AuthResponse(token, user);
    }
}
