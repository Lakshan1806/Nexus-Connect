package com.nexusconnect.servicebackend.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO representing a discovered LAN peer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveredPeerDto {

    private String username;
    private String ipAddress;
    private String additionalInfo;
    private Instant lastSeen;  // timestamp of last discovery
    private boolean stale;     // whether this peer info is outdated

    /**
     * Convenience constructor to accept lastSeen as a long (milliseconds).
     */
    public DiscoveredPeerDto(String username, String ipAddress, String additionalInfo, long lastSeenMillis, boolean stale) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.additionalInfo = additionalInfo;
        this.lastSeen = Instant.ofEpochMilli(lastSeenMillis);
        this.stale = stale;
    }

    /**
     * Convenience constructor to accept lastSeen as a long (seconds).
     */
    public static DiscoveredPeerDto fromSeconds(String username, String ipAddress, String additionalInfo, long lastSeenSeconds, boolean stale) {
        DiscoveredPeerDto dto = new DiscoveredPeerDto();
        dto.setUsername(username);
        dto.setIpAddress(ipAddress);
        dto.setAdditionalInfo(additionalInfo);
        dto.setLastSeen(Instant.ofEpochSecond(lastSeenSeconds));
        dto.setStale(stale);
        return dto;
    }
}
