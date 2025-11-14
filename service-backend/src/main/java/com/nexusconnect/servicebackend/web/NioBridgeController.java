package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.filetransfer.FileTransferService;
import com.nexusconnect.servicebackend.nio.NioChatServer;
import com.nexusconnect.servicebackend.web.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(NioBridgeController.class);
    private final NioChatServer nioChatServer;
    private final FileTransferService fileTransferService;

    public NioBridgeController(NioChatServer nioChatServer, FileTransferService fileTransferService) {
        this.nioChatServer = nioChatServer;
        this.fileTransferService = fileTransferService;
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
        
        // Start file transfer server if user provided a fileTcp port
        if (request.fileTcp() != null && request.fileTcp() > 0) {
            boolean started = fileTransferService.startServerForUser(request.user(), request.fileTcp());
            if (started) {
                log.info("Started file transfer server for user '{}' on port {}", request.user(), request.fileTcp());
            } else {
                log.warn("Failed to start file transfer server for user '{}'", request.user());
            }
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
        
        // Stop file transfer server for user
        fileTransferService.stopServerForUser(request.user());
        
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
