package com.nexusconnect.servicebackend.web.dto;

public record MessageAckResponse(boolean accepted, String reason, ChatMessageDto message) {
    public static MessageAckResponse accepted(ChatMessageDto message) {
        return new MessageAckResponse(true, null, message);
    }

    public static MessageAckResponse rejected(String reason) {
        return new MessageAckResponse(false, reason, null);
    }
}
