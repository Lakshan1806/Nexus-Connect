package com.nexusconnect.servicebackend.web.dto;

import com.nexusconnect.servicebackend.nio.NioChatServer;

public record ChatMessageDto(String from, String text, long timestampSeconds) {
    public static ChatMessageDto fromMessage(NioChatServer.ChatMessage message) {
        return new ChatMessageDto(message.from(), message.text(), message.timestampSeconds());
    }
}
