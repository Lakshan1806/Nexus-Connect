package com.nexusconnect.servicebackend.web.dto.auth;

import com.nexusconnect.servicebackend.user.AppUser;

import java.time.Instant;

public record UserProfileDto(
        Long id,
        String username,
        String email,
        Instant createdAt
) {
    public static UserProfileDto from(AppUser user) {
        return new UserProfileDto(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt());
    }
}
