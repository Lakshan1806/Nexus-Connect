package com.nexusconnect.servicebackend.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for LAN discovery request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveryRequestDto {

    @NotBlank(message = "Username is required")
    private String username;

    /**
     * Optional additional info about the client, e.g., platform, version
     */
    private String additionalInfo;
}
