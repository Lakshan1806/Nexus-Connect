package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.security.AuthenticatedUser;
import com.nexusconnect.servicebackend.security.JwtService;
import com.nexusconnect.servicebackend.user.AppUser;
import com.nexusconnect.servicebackend.user.UserService;
import com.nexusconnect.servicebackend.web.dto.auth.AuthResponse;
import com.nexusconnect.servicebackend.web.dto.auth.SignInRequest;
import com.nexusconnect.servicebackend.web.dto.auth.SignUpRequest;
import com.nexusconnect.servicebackend.web.dto.auth.UserProfileDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody SignUpRequest request) {
        AppUser user = userService.registerUser(request.email(), request.name(), request.password());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(AuthResponse.of(token, UserProfileDto.from(user)));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody SignInRequest request) {
        AppUser user = userService.authenticate(request.email(), request.password());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(AuthResponse.of(token, UserProfileDto.from(user)));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> me(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return userService.findByUsername(principal.username())
                .map(UserProfileDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
