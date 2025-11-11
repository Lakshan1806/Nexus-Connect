package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.discovery.UdpDiscoveryService;
import com.nexusconnect.servicebackend.web.dto.DiscoveryRequestDto;
import com.nexusconnect.servicebackend.web.dto.DiscoveredPeerDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/discovery")
@Validated
public class DiscoveryController {

    private final UdpDiscoveryService discoveryService;

    public DiscoveryController(UdpDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /**
     * Broadcast discovery request to LAN
     */
    @PostMapping("/broadcast")
    public ResponseEntity<String> broadcastDiscovery(@Valid @RequestBody DiscoveryRequestDto request) {
        discoveryService.broadcastDiscovery(request.getUsername(), request.getAdditionalInfo());
        return ResponseEntity.ok("Discovery broadcast sent");
    }

    /**
     * Get list of discovered peers
     */
    @GetMapping("/peers")
    public ResponseEntity<List<DiscoveredPeerDto>> getDiscoveredPeers() {
        List<DiscoveredPeerDto> peers = discoveryService.getDiscoveredPeers()
                .stream()
                .map(peer -> new DiscoveredPeerDto(
                        peer.username(),
                        peer.ipAddress(),
                        peer.additionalInfo(),
                        peer.lastSeen(),
                        peer.isStale()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.ok(peers);
    }

    /**
     * Get specific peer by username
     */
    @GetMapping("/peers/{username}")
    public ResponseEntity<DiscoveredPeerDto> getPeer(@PathVariable String username) {
        return discoveryService.getPeer(username)
                .map(peer -> new DiscoveredPeerDto(
                        peer.username(),
                        peer.ipAddress(),
                        peer.additionalInfo(),
                        peer.lastSeen(),
                        peer.isStale()
                ))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}