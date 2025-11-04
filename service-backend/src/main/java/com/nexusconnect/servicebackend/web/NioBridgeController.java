package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.nio.NioChatServer;
import com.nexusconnect.servicebackend.web.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/nio")
@Validated
public class NioBridgeController {
    private final NioChatServer nioChatServer;

    public NioBridgeController(NioChatServer nioChatServer) {
        this.nioChatServer = nioChatServer;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        String ip = request.ipOverride();
        if (ip == null || ip.isBlank()) {
            ip = Optional.ofNullable(httpRequest.getHeader("X-Forwarded-For"))
                    .map(v -> v.split(",", 2)[0])
                    .orElseGet(httpRequest::getRemoteAddr);
        }
        NioChatServer.LoginResult result =
                nioChatServer.loginHttp(request.user(), request.pass(), ip, request.fileTcp(), request.voiceUdp());
        if (!result.success()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(LoginResponse.failure(result.reason()));
        }
        List<OnlineUserDto> users = result.users().stream()
                .map(OnlineUserDto::fromPresence)
                .toList();
        List<ChatMessageDto> messages = result.messages().stream()
                .map(ChatMessageDto::fromMessage)
                .toList();
        return ResponseEntity.ok(LoginResponse.success(request.user(), users, messages));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        boolean removed = nioChatServer.logoutHttp(request.user());
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/message")
    public ResponseEntity<MessageAckResponse> postMessage(@Valid @RequestBody MessageRequest request) {
        Optional<NioChatServer.ChatMessage> message = nioChatServer.broadcastFrom(request.user(), request.text());
        if (message.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(MessageAckResponse.rejected("user must be logged in"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(MessageAckResponse.accepted(ChatMessageDto.fromMessage(message.get())));
    }

    @GetMapping("/users")
    public List<OnlineUserDto> onlineUsers() {
        return nioChatServer.onlineUsers().stream()
                .map(OnlineUserDto::fromPresence)
                .toList();
    }

    @GetMapping("/messages")
    public List<ChatMessageDto> recentMessages() {
        return nioChatServer.recentMessages().stream()
                .map(ChatMessageDto::fromMessage)
                .toList();
    }

    @GetMapping("/peer/{user}")
    public ResponseEntity<PeerResponse> peer(@PathVariable String user) {
        return nioChatServer.findPeer(user)
                .map(peer -> ResponseEntity.ok(new PeerResponse(user, peer.ip(), peer.fileTcp(), peer.voiceUdp(), peer.viaNio())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
