package com.nexusconnect.servicebackend.web;

import com.nexusconnect.servicebackend.filetransfer.FileTransferService;
import com.nexusconnect.servicebackend.web.dto.FileTransferProgressDto;
import com.nexusconnect.servicebackend.web.dto.FileTransferRequest;
import com.nexusconnect.servicebackend.web.dto.FileTransferResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * REST Controller for P2P File Transfer operations
 * Member 3 - TCP Socket-based Direct File Transfer
 */
@RestController
@RequestMapping("/api/filetransfer")
@Validated
public class FileTransferController {

    private final FileTransferService fileTransferService;

    public FileTransferController(FileTransferService fileTransferService) {
        this.fileTransferService = fileTransferService;
    }

    /**
     * Initiate a file transfer to a peer
     * POST /api/filetransfer/send
     */
    @PostMapping("/send")
    public CompletableFuture<ResponseEntity<FileTransferResponse>> sendFile(
            @Valid @RequestBody FileTransferRequest request) {
        
        return fileTransferService.sendFile(
                request.peerIp(),
                request.peerPort(),
                request.filePath(),
                request.senderUsername()
        ).thenApply(result -> {
            if (result.isSuccess()) {
                return ResponseEntity.ok(FileTransferResponse.success(
                        result.getTransferId(),
                        result.getFilename(),
                        result.getFilesize(),
                        result.getMessage()
                ));
            } else {
                return ResponseEntity.status(500).body(
                        FileTransferResponse.failure(result.getMessage())
                );
            }
        });
    }

    /**
     * Get active transfers for a user
     * GET /api/filetransfer/transfers/{username}
     */
    @GetMapping("/transfers/{username}")
    public ResponseEntity<List<FileTransferProgressDto>> getUserTransfers(
            @PathVariable String username) {
        
        Map<String, com.nexusconnect.servicebackend.filetransfer.FileTransferServer.FileTransferProgress> transfers =
                fileTransferService.getTransfersForUser(username);
        
        List<FileTransferProgressDto> dtos = transfers.values().stream()
                .map(FileTransferProgressDto::fromProgress)
                .toList();
        
        return ResponseEntity.ok(dtos);
    }

    /**
     * Get specific transfer progress
     * GET /api/filetransfer/transfer/{username}/{transferId}
     */
    @GetMapping("/transfer/{username}/{transferId}")
    public ResponseEntity<FileTransferProgressDto> getTransferProgress(
            @PathVariable String username,
            @PathVariable String transferId) {
        
        var progress = fileTransferService.getTransferProgress(username, transferId);
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(FileTransferProgressDto.fromProgress(progress));
    }

    /**
     * Check if file transfer server is running for a user
     * GET /api/filetransfer/status/{username}
     */
    @GetMapping("/status/{username}")
    public ResponseEntity<Map<String, Object>> getServerStatus(@PathVariable String username) {
        boolean running = fileTransferService.isServerRunning(username);
        return ResponseEntity.ok(Map.of(
                "username", username,
                "serverRunning", running
        ));
    }

    /**
     * List all received files in the download directory
     * GET /api/filetransfer/downloads
     */
    @GetMapping("/downloads")
    public ResponseEntity<List<Map<String, Object>>> listDownloads() {
        List<Map<String, Object>> files = new ArrayList<>();
        Path downloadDir = Paths.get("nexus_downloads");
        
        if (!Files.exists(downloadDir)) {
            return ResponseEntity.ok(files);
        }
        
        try (Stream<Path> stream = Files.list(downloadDir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(path -> {
                      File file = path.toFile();
                      Map<String, Object> fileInfo = new HashMap<>();
                      fileInfo.put("filename", file.getName());
                      fileInfo.put("size", file.length());
                      fileInfo.put("lastModified", file.lastModified());
                      fileInfo.put("path", file.getAbsolutePath());
                      files.add(fileInfo);
                  });
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
        
        return ResponseEntity.ok(files);
    }

    /**
     * Download a received file
     * GET /api/filetransfer/download/{filename}
     */
    @GetMapping("/download/{filename}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            // Sanitize filename to prevent directory traversal
            filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            
            Path filePath = Paths.get("nexus_downloads", filename);
            
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.notFound().build();
            }
            
            Resource resource = new FileSystemResource(filePath);
            
            // Determine content type
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
            }
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"" + filename + "\"")
                    .body(resource);
                    
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }
}
