package com.nexusconnect.servicebackend.web.dto;

import com.nexusconnect.servicebackend.nio.NioChatServer;

public record OnlineUserDto(String user, String ip, int fileTcp, int voiceUdp, boolean viaNio) {
    public static OnlineUserDto fromPresence(NioChatServer.UserPresence presence) {
        return new OnlineUserDto(
                presence.user(),
                presence.ip(),
                presence.fileTcp(),
                presence.voiceUdp(),
                presence.viaNio()
        );
    }
}
