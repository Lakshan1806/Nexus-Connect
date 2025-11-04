package com.nexusconnect.servicebackend.web.dto;

import java.util.List;

public record LoginResponse(
        boolean success,
        String reason,
        String user,
        List<OnlineUserDto> users,
        List<ChatMessageDto> messages
) {
    public static LoginResponse success(String user, List<OnlineUserDto> users, List<ChatMessageDto> messages) {
        return new LoginResponse(true, null, user, users, messages);
    }

    public static LoginResponse failure(String reason) {
        return new LoginResponse(false, reason, null, List.of(), List.of());
    }
}
