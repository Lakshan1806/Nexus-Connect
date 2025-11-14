package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record FileTransferRequest(
        @NotBlank(message = "Peer IP is required") String peerIp,
        @Positive(message = "Peer port must be positive") int peerPort,
        @NotBlank(message = "File path is required") String filePath,
        @NotBlank(message = "Sender username is required") String senderUsername
) {}
