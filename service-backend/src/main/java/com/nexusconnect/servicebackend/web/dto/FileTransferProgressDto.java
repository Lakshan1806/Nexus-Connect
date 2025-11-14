package com.nexusconnect.servicebackend.web.dto;

public record FileTransferProgressDto(
        String transferId,
        String filename,
        long totalBytes,
        long bytesTransferred,
        int progressPercent,
        double speedMBps,
        String sender,
        String receiver,
        boolean isReceiving,
        boolean completed,
        boolean failed,
        String errorMessage
) {
    public static FileTransferProgressDto fromProgress(
            com.nexusconnect.servicebackend.filetransfer.FileTransferServer.FileTransferProgress progress) {
        return new FileTransferProgressDto(
                progress.getTransferId(),
                progress.getFilename(),
                progress.getTotalBytes(),
                progress.getBytesTransferred(),
                progress.getProgressPercent(),
                progress.getSpeedMBps(),
                progress.getSender(),
                progress.getReceiver(),
                progress.isReceiving(),
                progress.isCompleted(),
                progress.isFailed(),
                progress.getErrorMessage()
        );
    }
}
