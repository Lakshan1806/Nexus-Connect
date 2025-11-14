package com.nexusconnect.servicebackend.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure Java STUN (Session Traversal Utilities for NAT) Server
 * 
 * This is a simplified STUN server implementation using java.net.DatagramSocket
 * for UDP communication. It helps WebRTC clients discover their public IP
 * address
 * and port for NAT traversal.
 * 
 * Network Programming Concepts Demonstrated:
 * - UDP socket programming with DatagramSocket and DatagramPacket
 * - Multithreading for handling concurrent UDP requests
 * - NAT traversal support for P2P connections
 * - Binary protocol implementation (STUN RFC 5389)
 * 
 * How it works:
 * 1. Client sends a STUN Binding Request to this server
 * 2. Server extracts client's public IP and port from the UDP packet
 * 3. Server sends a Binding Response containing the client's public address
 * 4. Client uses this info to establish P2P connections
 * 
 * This is a core component for WebRTC signaling and demonstrates pure Java
 * UDP programming as taught in network programming lectures.
 */
@Component
public class StunServer {
    private static final Logger log = LoggerFactory.getLogger(StunServer.class);

    // STUN protocol constants (RFC 5389)
    private static final int STUN_BINDING_REQUEST = 0x0001;
    private static final int STUN_BINDING_RESPONSE = 0x0101;
    private static final int STUN_MAGIC_COOKIE = 0x2112A442;
    private static final int STUN_HEADER_SIZE = 20;

    // STUN attribute types
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;

    @Value("${stun.server.port:3478}")
    private int stunPort;

    @Value("${stun.server.enabled:true}")
    private boolean enabled;

    private DatagramSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors(),
            r -> {
                Thread t = new Thread(r, "stun-server-worker");
                t.setDaemon(true);
                return t;
            });

    // Statistics
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong responseCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);

    /**
     * Starts the STUN server on application startup.
     */
    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("STUN server is disabled");
            return;
        }

        try {
            // Create UDP socket bound to STUN port
            serverSocket = new DatagramSocket(stunPort);
            running.set(true);

            log.info("🚀 STUN Server started on UDP port {}", stunPort);
            log.info("📡 Ready to help clients with NAT traversal");

            // Start listener thread
            executorService.submit(this::listenForRequests);

        } catch (SocketException e) {
            log.error("❌ Failed to start STUN server on port {}: {}", stunPort, e.getMessage());
            log.error("Make sure port {} is not already in use", stunPort);
        }
    }

    /**
     * Stops the STUN server on application shutdown.
     */
    @PreDestroy
    public void stop() {
        if (!running.get()) {
            return;
        }

        log.info("Shutting down STUN server...");
        running.set(false);

        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }

        executorService.shutdownNow();

        log.info("✅ STUN server stopped. Stats: requests={}, responses={}, errors={}",
                requestCount.get(), responseCount.get(), errorCount.get());
    }

    /**
     * Main listener loop - receives UDP packets and processes them.
     * Demonstrates DatagramSocket.receive() for UDP server programming.
     */
    private void listenForRequests() {
        log.info("STUN server listener thread started");

        // Buffer for incoming UDP packets
        byte[] buffer = new byte[1500]; // MTU size

        while (running.get()) {
            try {
                // Create packet to receive data
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // BLOCKING CALL: Wait for incoming UDP packet
                serverSocket.receive(packet);

                requestCount.incrementAndGet();

                // Extract client's address from the packet
                InetAddress clientAddress = packet.getAddress();
                int clientPort = packet.getPort();
                int packetLength = packet.getLength();

                log.debug("📥 Received STUN request from {}:{} ({} bytes)",
                        clientAddress.getHostAddress(), clientPort, packetLength);

                // Process request in thread pool (non-blocking)
                executorService.submit(() -> handleStunRequest(packet, clientAddress, clientPort));

            } catch (SocketException e) {
                if (running.get()) {
                    log.error("Socket error: {}", e.getMessage());
                    errorCount.incrementAndGet();
                }
                // Socket closed, exit loop
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error receiving STUN request: {}", e.getMessage());
                    errorCount.incrementAndGet();
                }
            }
        }

        log.info("STUN server listener thread stopped");
    }

    /**
     * Processes a STUN request and sends back a response.
     * 
     * This demonstrates:
     * - Parsing binary UDP packet data
     * - Creating STUN response messages
     * - Sending UDP responses with DatagramSocket.send()
     */
    private void handleStunRequest(DatagramPacket requestPacket, InetAddress clientAddress, int clientPort) {
        try {
            byte[] requestData = requestPacket.getData();
            int requestLength = requestPacket.getLength();

            // Validate minimum packet size
            if (requestLength < STUN_HEADER_SIZE) {
                log.warn("Invalid STUN packet from {}:{} - too short ({} bytes)",
                        clientAddress.getHostAddress(), clientPort, requestLength);
                errorCount.incrementAndGet();
                return;
            }

            // Parse STUN header
            ByteBuffer buffer = ByteBuffer.wrap(requestData, 0, requestLength);

            // Read message type (2 bytes)
            int messageType = buffer.getShort() & 0xFFFF;

            // Read message length (2 bytes)
            int messageLength = buffer.getShort() & 0xFFFF;

            // Read magic cookie (4 bytes)
            int magicCookie = buffer.getInt();

            // Read transaction ID (12 bytes)
            byte[] transactionId = new byte[12];
            buffer.get(transactionId);

            log.debug("STUN message type: 0x{}, length: {}, magic: 0x{}",
                    Integer.toHexString(messageType), messageLength, Integer.toHexString(magicCookie));

            // Validate STUN Binding Request
            if (messageType != STUN_BINDING_REQUEST) {
                log.warn("Unsupported STUN message type: 0x{}", Integer.toHexString(messageType));
                errorCount.incrementAndGet();
                return;
            }

            // Create STUN Binding Response
            byte[] response = createBindingResponse(
                    transactionId,
                    clientAddress,
                    clientPort);

            // Send response back to client
            DatagramPacket responsePacket = new DatagramPacket(
                    response,
                    response.length,
                    clientAddress,
                    clientPort);

            serverSocket.send(responsePacket);
            responseCount.incrementAndGet();

            log.info("✅ Sent STUN response to {}:{} - mapped address: {}:{}",
                    clientAddress.getHostAddress(), clientPort,
                    clientAddress.getHostAddress(), clientPort);

        } catch (Exception e) {
            log.error("Error handling STUN request from {}:{}: {}",
                    clientAddress.getHostAddress(), clientPort, e.getMessage());
            errorCount.incrementAndGet();
        }
    }

    /**
     * Creates a STUN Binding Response message.
     * 
     * Response format (RFC 5389):
     * - STUN Header (20 bytes)
     * - Message Type: 0x0101 (Binding Response)
     * - Message Length: length of attributes
     * - Magic Cookie: 0x2112A442
     * - Transaction ID: 12 bytes (same as request)
     * - Attributes:
     * - XOR-MAPPED-ADDRESS: client's public IP and port (XOR'd with magic cookie)
     */
    private byte[] createBindingResponse(byte[] transactionId, InetAddress clientAddress, int clientPort) {
        // Calculate attribute size
        // XOR-MAPPED-ADDRESS: type(2) + length(2) + reserved(1) + family(1) + port(2) +
        // address(4) = 12 bytes
        int attrSize = 12;
        int messageLength = attrSize;

        // Total response size: header(20) + attribute(12) = 32 bytes
        ByteBuffer buffer = ByteBuffer.allocate(STUN_HEADER_SIZE + attrSize);

        // --- STUN Header ---

        // Message Type: Binding Response (0x0101)
        buffer.putShort((short) STUN_BINDING_RESPONSE);

        // Message Length (excluding header)
        buffer.putShort((short) messageLength);

        // Magic Cookie
        buffer.putInt(STUN_MAGIC_COOKIE);

        // Transaction ID (copy from request)
        buffer.put(transactionId);

        // --- XOR-MAPPED-ADDRESS Attribute ---

        // Attribute Type: XOR-MAPPED-ADDRESS (0x0020)
        buffer.putShort((short) ATTR_XOR_MAPPED_ADDRESS);

        // Attribute Length: 8 bytes (reserved(1) + family(1) + port(2) + address(4))
        buffer.putShort((short) 8);

        // Reserved byte
        buffer.put((byte) 0x00);

        // Address Family: IPv4 = 0x01
        buffer.put((byte) 0x01);

        // XOR'd Port (port XOR'd with most significant 16 bits of magic cookie)
        int xorPort = clientPort ^ (STUN_MAGIC_COOKIE >>> 16);
        buffer.putShort((short) xorPort);

        // XOR'd IP Address (each byte XOR'd with magic cookie)
        byte[] ipBytes = clientAddress.getAddress();
        byte[] magicBytes = ByteBuffer.allocate(4).putInt(STUN_MAGIC_COOKIE).array();

        for (int i = 0; i < 4; i++) {
            buffer.put((byte) (ipBytes[i] ^ magicBytes[i]));
        }

        return buffer.array();
    }

    /**
     * Gets server statistics.
     */
    public StunStats getStats() {
        return new StunStats(
                running.get(),
                stunPort,
                requestCount.get(),
                responseCount.get(),
                errorCount.get());
    }

    /**
     * STUN server statistics.
     */
    public record StunStats(
            boolean running,
            int port,
            long requestCount,
            long responseCount,
            long errorCount) {
    }
}
