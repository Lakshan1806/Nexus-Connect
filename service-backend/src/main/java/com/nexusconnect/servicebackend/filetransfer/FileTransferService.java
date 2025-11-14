package com.nexusconnect.servicebackend.filetransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Service to manage file transfer servers for logged-in users
 * Each user gets their own FileTransferServer instance when they log in with a fileTcp port
 */
@Service
public class FileTransferService {
    private static final Logger log = LoggerFactory.getLogger(FileTransferService.class);
    
    private final Map<String, FileTransferServer> userServers = new ConcurrentHashMap<>();

    /**
     * Start a file transfer server for a user
     */
    public boolean startServerForUser(String username, int port) {
        if (userServers.containsKey(username)) {
            log.warn("File transfer server already running for user: {}", username);
            return false;
        }

        try {
            FileTransferServer server = new FileTransferServer(port, username);
            server.start();
            userServers.put(username, server);
            log.info("Started file transfer server for user '{}' on port {}", username, port);
            return true;
        } catch (IOException e) {
            log.error("Failed to start file transfer server for user '{}' on port {}", username, port, e);
            return false;
        }
    }

    /**
     * Stop the file transfer server for a user
     */
    public boolean stopServerForUser(String username) {
        FileTransferServer server = userServers.remove(username);
        if (server != null) {
            server.stop();
            log.info("Stopped file transfer server for user: {}", username);
            return true;
        }
        return false;
    }

    /**
     * Send a file to another peer
     */
    public CompletableFuture<FileTransferClient.FileTransferResult> sendFile(
            String peerIp, int peerPort, String filePath, String senderUsername) {
        
        Path path = Paths.get(filePath);
        return FileTransferClient.sendFile(peerIp, peerPort, path, senderUsername);
    }

    /**
     * Get transfer progress for a user
     */
    public Map<String, FileTransferServer.FileTransferProgress> getTransfersForUser(String username) {
        FileTransferServer server = userServers.get(username);
        if (server != null) {
            return server.getActiveTransfers();
        }
        return new ConcurrentHashMap<>();
    }

    /**
     * Get specific transfer progress
     */
    public FileTransferServer.FileTransferProgress getTransferProgress(String username, String transferId) {
        FileTransferServer server = userServers.get(username);
        if (server != null) {
            return server.getTransferProgress(transferId);
        }
        return null;
    }

    /**
     * Check if a user has a file transfer server running
     */
    public boolean isServerRunning(String username) {
        return userServers.containsKey(username);
    }

    /**
     * Stop all servers (called on shutdown)
     */
    public void stopAllServers() {
        userServers.forEach((username, server) -> {
            try {
                server.stop();
            } catch (Exception e) {
                log.error("Error stopping server for user: {}", username, e);
            }
        });
        userServers.clear();
        log.info("All file transfer servers stopped");
    }
}
