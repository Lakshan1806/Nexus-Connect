package com.nexusconnect.servicebackend.user;

public interface UserCredentialService {
    boolean verifyCredentials(String username, String rawPassword);
    boolean userExists(String username);
}
