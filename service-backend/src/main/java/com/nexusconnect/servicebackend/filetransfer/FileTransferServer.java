package com.nexusconnect.servicebackend.filetransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO-based P2P File Transfer Server
 * Each user runs this on their advertised fileTcp port
 * Handles incoming file transfer requests from other peers using non-blocking I/O
 */
public class FileTransferServer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(FileTransferServer.class);
    private static final int BUFFER_SIZE = 8192;
    private static final String DOWNLOAD_DIR = "nexus_downloads";

    private final int port;
    private final String username;
    private final Map<String, FileTransferProgress> activeTransfers;
    private final Map<SocketChannel, TransferSession> activeSessions;
    
    private volatile boolean running = false;
    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread selectorThread;

    public FileTransferServer(int port, String username) {
        this.port = port;
        this.username = username;
        this.activeTransfers = new ConcurrentHashMap<>();
        this.activeSessions = new ConcurrentHashMap<>();
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

        // Initialize NIO server
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        running = true;

        selectorThread = new Thread(this, "file-transfer-nio-" + port);
        selectorThread.start();

        log.info("NIO FileTransferServer started for user '{}' on port {}", username, port);
    }

    public synchronized void stop() {
        running = false;
        
        try {
            if (selector != null) {
                selector.wakeup();
            }
        } catch (Exception e) {
            log.error("Error waking selector", e);
        }

        try {
            if (selectorThread != null) {
                selectorThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close all active sessions
        activeSessions.keySet().forEach(channel -> {
            try {
                channel.close();
            } catch (IOException e) {
                log.error("Error closing channel", e);
            }
        });
        activeSessions.clear();

        try {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
            }
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
        } catch (IOException e) {
            log.error("Error closing server resources", e);
        }

        activeTransfers.clear();
        
        log.info("NIO FileTransferServer stopped for user '{}' on port {}", username, port);
    }

    @Override
    public void run() {
        while (running) {
            try {
                // Wait for events with 1 second timeout
                int readyCount = selector.select(1000);
                
                if (!running) {
                    break;
                }

                if (readyCount == 0) {
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        } else if (key.isReadable()) {
                            handleRead(key);
                        } else if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (Exception e) {
                        log.error("Error handling key operation", e);
                        closeChannel(key);
                    }
                }

            } catch (IOException e) {
                if (running) {
                    log.error("Error in selector loop", e);
                }
            }
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverSocketChannel.accept();
        
        if (clientChannel == null) {
            return;
        }

        clientChannel.configureBlocking(false);
        clientChannel.register(selector, SelectionKey.OP_READ);
        
        TransferSession session = new TransferSession(clientChannel);
        activeSessions.put(clientChannel, session);
        
        log.info("Accepted new file transfer connection from: {}", 
                clientChannel.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        TransferSession session = activeSessions.get(channel);
        
        if (session == null) {
            closeChannel(key);
            return;
        }

        try {
            session.read(channel);
            
            if (session.isReadyToWrite()) {
                key.interestOps(SelectionKey.OP_WRITE);
            }
        } catch (Exception e) {
            log.error("Error reading from channel", e);
            session.markFailed(e.getMessage());
            closeChannel(key);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        TransferSession session = activeSessions.get(channel);
        
        if (session == null) {
            closeChannel(key);
            return;
        }

        try {
            session.write(channel);
            
            if (session.isReadComplete()) {
                // Continue reading file data
                key.interestOps(SelectionKey.OP_READ);
            } else if (session.isTransferComplete()) {
                log.info("Transfer complete for session: {}", session.getTransferId());
                activeSessions.remove(channel);
                closeChannel(key);
            }
        } catch (Exception e) {
            log.error("Error writing to channel", e);
            session.markFailed(e.getMessage());
            closeChannel(key);
        }
    }

    private void closeChannel(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException e) {
            log.error("Error closing channel", e);
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

    /**
     * Represents a file transfer session with NIO
     */
    private class TransferSession {
        private enum State {
            READING_HEADER,
            WRITING_ACK,
            READING_FILE_DATA,
            WRITING_SUCCESS,
            COMPLETED
        }

        private State state = State.READING_HEADER;
        private final ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        private ByteBuffer writeBuffer;
        
        private String transferId;
        private String filename;
        private long filesize;
        private String sender;
        private Path outputPath;
        private FileChannel fileChannel;
        private long bytesReceived = 0;
        private long lastLogTime;
        private FileTransferProgress progress;

        public TransferSession(SocketChannel channel) {
            this.lastLogTime = System.currentTimeMillis();
        }

        public void read(SocketChannel channel) throws IOException {
            int bytesRead = channel.read(readBuffer);
            
            if (bytesRead == -1) {
                throw new IOException("Connection closed by peer");
            }

            if (state == State.READING_HEADER) {
                readHeader();
            } else if (state == State.READING_FILE_DATA) {
                readFileData();
            }
        }

        public void write(SocketChannel channel) throws IOException {
            if (writeBuffer != null && writeBuffer.hasRemaining()) {
                channel.write(writeBuffer);
            }

            if (writeBuffer != null && !writeBuffer.hasRemaining()) {
                writeBuffer = null;
                
                if (state == State.WRITING_ACK) {
                    state = State.READING_FILE_DATA;
                } else if (state == State.WRITING_SUCCESS) {
                    state = State.COMPLETED;
                    if (progress != null) {
                        progress.markCompleted();
                    }
                    closeFileChannel();
                    log.info("File transfer completed: {} -> {}", filename, outputPath);
                }
            }
        }

        private void readHeader() throws IOException {
            readBuffer.flip();
            
            // Check if we have enough data for the header (look for newline)
            byte[] data = new byte[readBuffer.remaining()];
            readBuffer.get(data);
            String received = new String(data, StandardCharsets.UTF_8);
            
            if (!received.contains("\n")) {
                // Need more data
                readBuffer.compact();
                return;
            }

            int newlineIndex = received.indexOf('\n');
            String header = received.substring(0, newlineIndex);
            
            // Protocol: SEND_FILE|transferId|filename|filesize|senderUsername
            String[] parts = header.split("\\|");
            
            if (parts.length < 5 || !parts[0].equals("SEND_FILE")) {
                throw new IOException("Invalid protocol: " + header);
            }

            transferId = parts[1];
            filename = parts[2];
            filesize = Long.parseLong(parts[3]);
            sender = parts[4];

            log.info("Receiving file '{}' ({} bytes) from '{}' (transferId: {})", 
                    filename, filesize, sender, transferId);

            // Sanitize filename
            filename = sanitizeFilename(filename);
            outputPath = Paths.get(DOWNLOAD_DIR, filename);

            // Check if file already exists, append number
            int counter = 1;
            while (Files.exists(outputPath)) {
                int dotIndex = filename.lastIndexOf('.');
                if (dotIndex > 0) {
                    String baseName = filename.substring(0, dotIndex);
                    String extension = filename.substring(dotIndex);
                    outputPath = Paths.get(DOWNLOAD_DIR, baseName + "_" + counter + extension);
                } else {
                    outputPath = Paths.get(DOWNLOAD_DIR, filename + "_" + counter);
                }
                counter++;
            }

            // Open file channel for writing
            fileChannel = FileChannel.open(outputPath, 
                    StandardOpenOption.CREATE, 
                    StandardOpenOption.WRITE, 
                    StandardOpenOption.TRUNCATE_EXISTING);

            // Create progress tracker
            progress = new FileTransferProgress(
                    transferId, filename, filesize, sender, username, true
            );
            activeTransfers.put(transferId, progress);

            // Prepare acknowledgment
            String ack = "OK|" + outputPath.getFileName().toString() + "\n";
            writeBuffer = ByteBuffer.wrap(ack.getBytes(StandardCharsets.UTF_8));
            state = State.WRITING_ACK;

            // Put remaining data back for file reading
            if (newlineIndex + 1 < received.length()) {
                byte[] remaining = received.substring(newlineIndex + 1).getBytes(StandardCharsets.UTF_8);
                readBuffer.clear();
                readBuffer.put(remaining);
            } else {
                readBuffer.clear();
            }
        }

        private void readFileData() throws IOException {
            readBuffer.flip();
            
            long remainingBytes = filesize - bytesReceived;
            int toWrite = (int) Math.min(readBuffer.remaining(), remainingBytes);
            
            if (toWrite > 0) {
                // Limit the buffer to only write the needed bytes
                ByteBuffer limitedBuffer = readBuffer.slice();
                limitedBuffer.limit(toWrite);
                
                int written = fileChannel.write(limitedBuffer);
                bytesReceived += written;
                readBuffer.position(readBuffer.position() + written);
                
                if (progress != null) {
                    progress.addBytesTransferred(written);
                }

                // Log progress every 2 seconds
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 2000) {
                    log.info("Transfer {}: {}/{}% complete", 
                            transferId, bytesReceived, progress != null ? progress.getProgressPercent() : 0);
                    lastLogTime = now;
                }
            }

            readBuffer.compact();

            // Check if transfer is complete
            if (bytesReceived >= filesize) {
                closeFileChannel();
                
                // Prepare success response
                String success = "SUCCESS\n";
                writeBuffer = ByteBuffer.wrap(success.getBytes(StandardCharsets.UTF_8));
                state = State.WRITING_SUCCESS;
            }
        }

        private void closeFileChannel() {
            if (fileChannel != null && fileChannel.isOpen()) {
                try {
                    fileChannel.close();
                } catch (IOException e) {
                    log.error("Error closing file channel", e);
                }
            }
        }

        public void markFailed(String error) {
            closeFileChannel();
            if (progress != null) {
                progress.markFailed(error);
            }
        }

        public boolean isReadyToWrite() {
            return writeBuffer != null && writeBuffer.hasRemaining();
        }

        public boolean isReadComplete() {
            return state == State.READING_FILE_DATA && bytesReceived < filesize;
        }

        public boolean isTransferComplete() {
            return state == State.COMPLETED;
        }

        public String getTransferId() {
            return transferId;
        }
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
