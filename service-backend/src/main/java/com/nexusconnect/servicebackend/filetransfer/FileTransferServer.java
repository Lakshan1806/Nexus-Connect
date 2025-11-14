package com.nexusconnect.servicebackend.filetransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP-based P2P File Transfer Server
 * Each user runs this on their advertised fileTcp port
 * Handles incoming file transfer requests from other peers
 */
public class FileTransferServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(FileTransferServer.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int SO_TIMEOUT = 30000; // 30 seconds
    private static final String DOWNLOAD_DIR = "nexus_downloads";

    private final int port;
    private final String username;
    private final ExecutorService executor;
    private final Map<String, FileTransferProgress> activeTransfers;
    
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public FileTransferServer(int port, String username) {
        this.port = port;
        this.username = username;
        this.executor = Executors.newFixedThreadPool(5); // Max 5 concurrent transfers
        this.activeTransfers = new ConcurrentHashMap<>();
    }

    public synchronized void start() throws IOException {
        if (running) {
            log.warn("FileTransferServer already running on port {}", port);
            return;
        }

        // Create download directory
        Path downloadPath = Paths.get(DOWNLOAD_DIR);
        if (!Files.exists(downloadPath)) {
            Files.createDirectories(downloadPath);
        }

        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(1000); // 1 second timeout for accept
        running = true;

        acceptThread = new Thread(this, "file-transfer-accept-" + port);
        acceptThread.start();

        log.info("FileTransferServer started for user '{}' on port {}", username, port);
    }

    public synchronized void stop() {
        running = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.error("Error closing server socket", e);
        }

        try {
            if (acceptThread != null) {
                acceptThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executor.shutdownNow();
        activeTransfers.clear();
        
        log.info("FileTransferServer stopped for user '{}' on port {}", username, port);
    }

    @Override
    public void run() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
            } catch (SocketTimeoutException e) {
                // Normal timeout, continue
            } catch (IOException e) {
                if (running) {
                    log.error("Error accepting connection", e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        String transferId = null;
        try {
            socket.setSoTimeout(SO_TIMEOUT);
            
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            // Protocol: SEND_FILE|transferId|filename|filesize|senderUsername
            String header = in.readUTF();
            String[] parts = header.split("\\|");
            
            if (parts.length < 5 || !parts[0].equals("SEND_FILE")) {
                out.writeUTF("ERROR|Invalid protocol");
                return;
            }

            transferId = parts[1];
            String filename = parts[2];
            long filesize = Long.parseLong(parts[3]);
            String sender = parts[4];

            log.info("Receiving file '{}' ({} bytes) from '{}' (transferId: {})", 
                    filename, filesize, sender, transferId);

            // Sanitize filename
            filename = sanitizeFilename(filename);
            Path outputPath = Paths.get(DOWNLOAD_DIR, filename);

            // Check if file already exists, append number
            int counter = 1;
            while (Files.exists(outputPath)) {
                String baseName = filename.substring(0, filename.lastIndexOf('.'));
                String extension = filename.substring(filename.lastIndexOf('.'));
                outputPath = Paths.get(DOWNLOAD_DIR, baseName + "_" + counter + extension);
                counter++;
            }

            // Send acknowledgment
            out.writeUTF("OK|" + outputPath.getFileName().toString());
            out.flush();

            // Create progress tracker
            FileTransferProgress progress = new FileTransferProgress(
                    transferId, filename, filesize, sender, username, true
            );
            activeTransfers.put(transferId, progress);

            // Receive file data
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long remaining = filesize;
                long lastLogTime = System.currentTimeMillis();

                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int bytesRead = in.read(buffer, 0, toRead);
                    
                    if (bytesRead == -1) {
                        throw new IOException("Unexpected end of stream");
                    }

                    fos.write(buffer, 0, bytesRead);
                    remaining -= bytesRead;
                    progress.addBytesTransferred(bytesRead);

                    // Log progress every 2 seconds
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 2000) {
                        log.info("Transfer {}: {}/{}% complete", 
                                transferId, progress.getBytesTransferred(), progress.getProgressPercent());
                        lastLogTime = now;
                    }
                }

                progress.markCompleted();
                out.writeUTF("SUCCESS");
                log.info("File transfer completed: {} -> {}", filename, outputPath);
            }

        } catch (Exception e) {
            log.error("Error handling file transfer (transferId: {})", transferId, e);
            if (transferId != null) {
                FileTransferProgress progress = activeTransfers.get(transferId);
                if (progress != null) {
                    progress.markFailed(e.getMessage());
                }
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String sanitizeFilename(String filename) {
        // Remove path traversal attempts
        filename = filename.replaceAll("[/\\\\]", "_");
        // Remove potentially dangerous characters
        filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        return filename;
    }

    public Map<String, FileTransferProgress> getActiveTransfers() {
        return new ConcurrentHashMap<>(activeTransfers);
    }

    public FileTransferProgress getTransferProgress(String transferId) {
        return activeTransfers.get(transferId);
    }

    public static class FileTransferProgress {
        private final String transferId;
        private final String filename;
        private final long totalBytes;
        private final String sender;
        private final String receiver;
        private final boolean isReceiving;
        private final AtomicLong bytesTransferred;
        private final long startTime;
        private volatile boolean completed;
        private volatile boolean failed;
        private volatile String errorMessage;

        public FileTransferProgress(String transferId, String filename, long totalBytes,
                                   String sender, String receiver, boolean isReceiving) {
            this.transferId = transferId;
            this.filename = filename;
            this.totalBytes = totalBytes;
            this.sender = sender;
            this.receiver = receiver;
            this.isReceiving = isReceiving;
            this.bytesTransferred = new AtomicLong(0);
            this.startTime = System.currentTimeMillis();
            this.completed = false;
            this.failed = false;
        }

        public void addBytesTransferred(long bytes) {
            bytesTransferred.addAndGet(bytes);
        }

        public void markCompleted() {
            this.completed = true;
        }

        public void markFailed(String error) {
            this.failed = true;
            this.errorMessage = error;
        }

        public String getTransferId() { return transferId; }
        public String getFilename() { return filename; }
        public long getTotalBytes() { return totalBytes; }
        public String getSender() { return sender; }
        public String getReceiver() { return receiver; }
        public boolean isReceiving() { return isReceiving; }
        public long getBytesTransferred() { return bytesTransferred.get(); }
        public boolean isCompleted() { return completed; }
        public boolean isFailed() { return failed; }
        public String getErrorMessage() { return errorMessage; }
        
        public int getProgressPercent() {
            if (totalBytes == 0) return 0;
            return (int) ((bytesTransferred.get() * 100) / totalBytes);
        }

        public double getSpeedMBps() {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed == 0) return 0;
            return (bytesTransferred.get() / 1024.0 / 1024.0) / (elapsed / 1000.0);
        }
    }
}
