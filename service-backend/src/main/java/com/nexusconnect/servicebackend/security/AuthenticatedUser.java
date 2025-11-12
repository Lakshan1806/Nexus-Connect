package com.nexusconnect.servicebackend.security;

import java.security.Principal;

public record AuthenticatedUser(Long id, String username, String email) implements Principal {
    @Override
    public String getName() {
        return username;
    }
}
