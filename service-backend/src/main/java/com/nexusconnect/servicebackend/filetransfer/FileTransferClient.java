package com.nexusconnect.servicebackend.filetransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * NIO-based P2P File Transfer Client
 * Connects directly to another peer's fileTcp port to send files using non-blocking I/O
 */
public class FileTransferClient {
    private static final Logger log = LoggerFactory.getLogger(FileTransferClient.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT = 10000; // 10 seconds

    /**
     * Send a file to a peer using NIO
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
            SocketChannel socketChannel = null;
            FileChannel fileChannel = null;
            
            try {
                if (!Files.exists(filePath)) {
                    throw new IOException("File not found: " + filePath);
                }

                String filename = filePath.getFileName().toString();
                long filesize = Files.size(filePath);

                log.info("Initiating NIO file transfer to {}:{} - {} ({} bytes)", 
                        peerIp, peerPort, filename, filesize);

                // Open socket channel
                socketChannel = SocketChannel.open();
                socketChannel.socket().connect(
                        new InetSocketAddress(peerIp, peerPort), 
                        CONNECT_TIMEOUT
                );
                socketChannel.configureBlocking(true); // Use blocking mode for simplicity

                // Send header: SEND_FILE|transferId|filename|filesize|senderUsername
                String header = String.join("|", 
                        "SEND_FILE", transferId, filename, String.valueOf(filesize), senderUsername);
                header += "\n";
                
                ByteBuffer headerBuffer = ByteBuffer.wrap(header.getBytes(StandardCharsets.UTF_8));
                while (headerBuffer.hasRemaining()) {
                    socketChannel.write(headerBuffer);
                }

                log.info("Sent file transfer header, waiting for acknowledgment...");

                // Wait for acknowledgment: OK|savedFilename
                ByteBuffer ackBuffer = ByteBuffer.allocate(256);
                StringBuilder ackBuilder = new StringBuilder();
                
                while (true) {
                    ackBuffer.clear();
                    int bytesRead = socketChannel.read(ackBuffer);
                    
                    if (bytesRead == -1) {
                        throw new IOException("Connection closed while waiting for acknowledgment");
                    }
                    
                    ackBuffer.flip();
                    byte[] data = new byte[ackBuffer.remaining()];
                    ackBuffer.get(data);
                    ackBuilder.append(new String(data, StandardCharsets.UTF_8));
                    
                    if (ackBuilder.toString().contains("\n")) {
                        break;
                    }
                }

                String response = ackBuilder.toString().trim();
                String[] responseParts = response.split("\\|");
                
                if (!responseParts[0].equals("OK")) {
                    throw new IOException("Peer rejected file transfer: " + response);
                }

                String savedFilename = responseParts.length > 1 ? responseParts[1] : filename;
                log.info("Peer accepted file transfer (saved as: {})", savedFilename);

                // Open file channel and send file data
                fileChannel = FileChannel.open(filePath, StandardOpenOption.READ);
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                
                long sent = 0;
                long lastLogTime = System.currentTimeMillis();

                while (fileChannel.read(buffer) > 0) {
                    buffer.flip();
                    
                    while (buffer.hasRemaining()) {
                        int written = socketChannel.write(buffer);
                        sent += written;
                    }
                    
                    buffer.clear();

                    // Log progress every 2 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 2000) {
                        int percent = (int) ((sent * 100) / filesize);
                        log.info("Transfer {}: {}% complete ({}/{} bytes)", 
                                transferId, percent, sent, filesize);
                        lastLogTime = now;
                    }
                }

                log.info("File data sent, waiting for final confirmation...");

                // Wait for final confirmation: SUCCESS
                ByteBuffer successBuffer = ByteBuffer.allocate(256);
                StringBuilder successBuilder = new StringBuilder();
                
                while (true) {
                    successBuffer.clear();
                    int bytesRead = socketChannel.read(successBuffer);
                    
                    if (bytesRead == -1) {
                        throw new IOException("Connection closed while waiting for success confirmation");
                    }
                    
                    successBuffer.flip();
                    byte[] data = new byte[successBuffer.remaining()];
                    successBuffer.get(data);
                    successBuilder.append(new String(data, StandardCharsets.UTF_8));
                    
                    if (successBuilder.toString().contains("\n")) {
                        break;
                    }
                }

                String finalResponse = successBuilder.toString().trim();
                if (!finalResponse.equals("SUCCESS")) {
                    throw new IOException("Transfer failed: " + finalResponse);
                }

                log.info("NIO file transfer completed successfully: {} -> {}:{}", 
                        filename, peerIp, peerPort);
                
                return new FileTransferResult(true, transferId, filename, filesize, 
                        "Transfer completed successfully");

            } catch (Exception e) {
                log.error("NIO file transfer failed (transferId: {})", transferId, e);
                return new FileTransferResult(false, transferId, 
                        filePath != null ? filePath.getFileName().toString() : "unknown",
                        0, "Transfer failed: " + e.getMessage());
            } finally {
                // Close resources
                if (fileChannel != null) {
                    try {
                        fileChannel.close();
                    } catch (IOException ignored) {
                    }
                }
                if (socketChannel != null) {
                    try {
                        socketChannel.close();
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
