package com.nexusconnect.servicebackend.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Service
public class UdpDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(UdpDiscoveryService.class);
    private static final int DISCOVERY_PORT = 9876;
    private static final int BUFFER_SIZE = 1024;
    private static final String BROADCAST_ADDRESS = "255.255.255.255";
    private static final String DISCOVERY_REQUEST = "NEXUS_DISCOVER";
    private static final String DISCOVERY_RESPONSE_PREFIX = "NEXUS_RESPONSE";

    private final Map<String, DiscoveredPeer> discoveredPeers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private volatile DatagramSocket socket;
    private volatile boolean running = false;
    private Thread listenerThread;

    // Start the UDP discovery service
    public synchronized void start() throws IOException {
        if (running) {
            log.warn("UDP Discovery Service already running");
            return;
        }

        socket = new DatagramSocket(DISCOVERY_PORT);
        socket.setBroadcast(true);
        socket.setSoTimeout(500); // 500ms timeout for non-blocking receive
        running = true;

        // Start listener thread
        listenerThread = new Thread(this::listenForMessages, "udp-discovery-listener");
        listenerThread.start();

        // Schedule cleanup of stale peers every 30 seconds
        scheduler.scheduleAtFixedRate(this::cleanupStalePeers, 30, 30, TimeUnit.SECONDS);

        log.info("UDP Discovery Service started on port {}", DISCOVERY_PORT);
    }

    // Stop the service
    public synchronized void stop() {
        running = false;
        scheduler.shutdownNow();

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        if (listenerThread != null) {
            try {
                listenerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        discoveredPeers.clear();
        log.info("UDP Discovery Service stopped");
    }

    // Broadcast discovery request to LAN
    public void broadcastDiscovery(String username, String additionalInfo) {
        if (!running) {
            log.warn("Cannot broadcast: service not running");
            return;
        }

        try {
            String message = String.format("%s:%s:%s",
                    DISCOVERY_REQUEST, username, additionalInfo);
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);

            DatagramPacket packet = new DatagramPacket(
                    buffer,
                    buffer.length,
                    InetAddress.getByName(BROADCAST_ADDRESS),
                    DISCOVERY_PORT
            );

            socket.send(packet);
            log.info("Broadcasted discovery request from user: {}", username);

        } catch (IOException e) {
            log.error("Failed to broadcast discovery request", e);
        }


        try {
            String localIp = InetAddress.getLocalHost().getHostAddress();
            discoveredPeers.put(username, new DiscoveredPeer(username, localIp, additionalInfo, System.currentTimeMillis()));
            log.info("Self Broadcasted discovery added.");

        } catch (UnknownHostException e) {
            log.error("Cannot get local IP", e);
        }



    }

    // Get list of discovered peers
    public List<DiscoveredPeer> getDiscoveredPeers() {
        return new ArrayList<>(discoveredPeers.values());
    }

    // Get specific peer by username
    public Optional<DiscoveredPeer> getPeer(String username) {
        return Optional.ofNullable(discoveredPeers.get(username));
    }

    // Listen for incoming UDP messages
    private void listenForMessages() {
        byte[] buffer = new byte[BUFFER_SIZE];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String message = new String(
                        packet.getData(),
                        0,
                        packet.getLength(),
                        StandardCharsets.UTF_8
                );

                String senderIp = packet.getAddress().getHostAddress();

                // Process the message
                handleIncomingMessage(message, senderIp);

            } catch (SocketTimeoutException e) {
                // Normal timeout, continue loop
            } catch (IOException e) {
                if (running) {
                    log.error("Error receiving UDP packet", e);
                }
            }
        }
    }

    // Handle different types of incoming messages
    private void handleIncomingMessage(String message, String senderIp) {
        String[] parts = message.split(":", 3);

        if (parts.length < 2) {
            log.warn("Invalid message format from {}: {}", senderIp, message);
            return;
        }

        String messageType = parts[0];
        String username = parts[1];
        String additionalInfo = parts.length > 2 ? parts[2] : "";

        log.info("Received message from {}: {}",senderIp,messageType);

        switch (messageType) {
            case DISCOVERY_REQUEST:
                // Someone is looking for peers, respond to them
                handleDiscoveryRequest(username, senderIp);
                break;

            case DISCOVERY_RESPONSE_PREFIX:
                // Someone responded to our discovery request
                handleDiscoveryResponse(username, senderIp, additionalInfo);
                break;

            default:
                log.warn("Unknown message type: {}", messageType);
        }
    }

    // Handle discovery request from another peer
    private void handleDiscoveryRequest(String requesterUsername, String requesterIp) {
        try {
            // Don't respond to our own broadcasts
            if (isLocalAddress(requesterIp)) {
                return;
            }

            // Prepare response with our information
            String hostname = InetAddress.getLocalHost().getHostName();
            String responseMessage = String.format("%s:%s:%s",
                    DISCOVERY_RESPONSE_PREFIX,
                    "LocalUser", // This should come from session
                    hostname
            );

            byte[] buffer = responseMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                    buffer,
                    buffer.length,
                    InetAddress.getByName(requesterIp),
                    DISCOVERY_PORT
            );

            socket.send(packet);
            log.debug("Sent discovery response to {} at {}", requesterUsername, requesterIp);

        } catch (IOException e) {
            log.error("Failed to send discovery response", e);
        }
    }

    // Handle discovery response from a peer
    private void handleDiscoveryResponse(String username, String ip, String additionalInfo) {
        DiscoveredPeer peer = new DiscoveredPeer(
                username,
                ip,
                additionalInfo,
                System.currentTimeMillis()
        );

        discoveredPeers.put(username, peer);
        log.info("Discovered peer: {} at {}", username, ip);
    }

    // Remove peers that haven't been seen recently
    private void cleanupStalePeers() {
        long now = System.currentTimeMillis();
        long staleThreshold = 120_000; // 2 minutes

        discoveredPeers.entrySet().removeIf(entry -> {
            boolean isStale = (now - entry.getValue().lastSeen()) > staleThreshold;
            if (isStale) {
                log.debug("Removing stale peer: {}", entry.getKey());
            }
            return isStale;
        });
    }

    // Check if IP address is local
    private boolean isLocalAddress(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() ||
                    addr.isLinkLocalAddress() ||
                    NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception e) {
            return false;
        }
    }

    // Discovered peer record
    public record DiscoveredPeer(
            String username,
            String ipAddress,
            String additionalInfo,
            long lastSeen
    ) {
        public boolean isStale() {
            return System.currentTimeMillis() - lastSeen > 120_000;
        }
    }
}