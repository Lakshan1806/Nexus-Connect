package com.nexusconnect.servicebackend.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class UserService implements UserCredentialService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserService(AppUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser registerUser(String email, String username, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);
        String normalizedUsername = username.trim();

        if (repository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalArgumentException("Email is already registered");
        }
        if (repository.existsByUsernameIgnoreCase(normalizedUsername)) {
            throw new IllegalArgumentException("Username is already taken");
        }

        AppUser user = new AppUser(
                normalizedEmail,
                normalizedUsername,
                passwordEncoder.encode(rawPassword)
        );
        AppUser saved = repository.save(user);
        log.info("Registered new user '{}' ({})", saved.getUsername(), saved.getEmail());
        return saved;
    }

    public AppUser authenticate(String email, String rawPassword) {
        AppUser user = repository.findByEmailIgnoreCase(normalizeEmail(email))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return user;
    }

    public Optional<AppUser> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return repository.findByUsernameIgnoreCase(username.trim());
    }

    @Override
    public boolean verifyCredentials(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            return false;
        }
        return findByUsername(username)
                .map(user -> passwordEncoder.matches(rawPassword, user.getPasswordHash()))
                .orElse(false);
    }

    @Override
    public boolean userExists(String username) {
        if (username == null) {
            return false;
        }
        return repository.existsByUsernameIgnoreCase(username.trim());
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
