package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to initiate a peer-to-peer voice session between two clients.
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
public record VoiceInitRequest(
        @NotBlank(message = "Initiator username is required") String initiator,

        @NotBlank(message = "Target username is required") String target,

        @NotNull(message = "Local UDP port is required") Integer localUdpPort) {
}
