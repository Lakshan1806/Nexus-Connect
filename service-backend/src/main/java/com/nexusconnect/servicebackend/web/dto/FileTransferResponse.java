package com.nexusconnect.servicebackend.web.dto;

public record FileTransferResponse(
        boolean success,
        String transferId,
        String filename,
        long filesize,
        String message
) {
    public static FileTransferResponse success(String transferId, String filename, long filesize, String message) {
        return new FileTransferResponse(true, transferId, filename, filesize, message);
    }

    public static FileTransferResponse failure(String message) {
        return new FileTransferResponse(false, null, null, 0, message);
    }
}
