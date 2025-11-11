package com.nexusconnect.servicebackend.filetransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * TCP-based P2P File Transfer Client
 * Connects directly to another peer's fileTcp port to send files
 */
public class FileTransferClient {
    private static final Logger log = LoggerFactory.getLogger(FileTransferClient.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 10000; // 10 seconds
    private static final int SO_TIMEOUT = 30000; // 30 seconds

    /**
     * Send a file to a peer
     * @param peerIp Peer's IP address
     * @param peerPort Peer's file transfer TCP port
     * @param filePath Path to the file to send
     * @param senderUsername Username of sender
     * @return CompletableFuture with transfer result
     */
    public static CompletableFuture<FileTransferResult> sendFile(
            String peerIp, int peerPort, Path filePath, String senderUsername) {
        
        return CompletableFuture.supplyAsync(() -> {
            String transferId = UUID.randomUUID().toString();
            Socket socket = null;
            
            try {
                if (!Files.exists(filePath)) {
                    throw new IOException("File not found: " + filePath);
                }

                String filename = filePath.getFileName().toString();
                long filesize = Files.size(filePath);

                log.info("Initiating file transfer to {}:{} - {} ({} bytes)", 
                        peerIp, peerPort, filename, filesize);

                // Connect to peer
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(peerIp, peerPort), CONNECT_TIMEOUT);
                socket.setSoTimeout(SO_TIMEOUT);

                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                // Send header: SEND_FILE|transferId|filename|filesize|senderUsername
                String header = String.join("|", 
                        "SEND_FILE", transferId, filename, String.valueOf(filesize), senderUsername);
                out.writeUTF(header);
                out.flush();

                // Wait for acknowledgment
                String response = in.readUTF();
                String[] responseParts = response.split("\\|");
                
                if (!responseParts[0].equals("OK")) {
                    throw new IOException("Peer rejected file transfer: " + response);
                }

                String savedFilename = responseParts.length > 1 ? responseParts[1] : filename;
                log.info("Peer accepted file transfer (saved as: {})", savedFilename);

                // Send file data
                try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long sent = 0;
                    int bytesRead;
                    long lastLogTime = System.currentTimeMillis();

                    while ((bytesRead = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        sent += bytesRead;

                        // Log progress every 2 seconds
                        long now = System.currentTimeMillis();
                        if (now - lastLogTime > 2000) {
                            int percent = (int) ((sent * 100) / filesize);
                            log.info("Transfer {}: {}% complete ({}/{} bytes)", 
                                    transferId, percent, sent, filesize);
                            lastLogTime = now;
                        }
                    }
                    out.flush();

                    // Wait for final confirmation
                    String finalResponse = in.readUTF();
                    if (!finalResponse.equals("SUCCESS")) {
                        throw new IOException("Transfer failed: " + finalResponse);
                    }

                    log.info("File transfer completed successfully: {} -> {}:{}", 
                            filename, peerIp, peerPort);
                    
                    return new FileTransferResult(true, transferId, filename, filesize, 
                            "Transfer completed successfully");
                }

            } catch (Exception e) {
                log.error("File transfer failed (transferId: {})", transferId, e);
                return new FileTransferResult(false, transferId, 
                        filePath != null ? filePath.getFileName().toString() : "unknown",
                        0, "Transfer failed: " + e.getMessage());
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    public static class FileTransferResult {
        private final boolean success;
        private final String transferId;
        private final String filename;
        private final long filesize;
        private final String message;

        public FileTransferResult(boolean success, String transferId, String filename, 
                                 long filesize, String message) {
            this.success = success;
            this.transferId = transferId;
            this.filename = filename;
            this.filesize = filesize;
            this.message = message;
        }

        public boolean isSuccess() { return success; }
        public String getTransferId() { return transferId; }
        public String getFilename() { return filename; }
        public long getFilesize() { return filesize; }
        public String getMessage() { return message; }
    }
}
