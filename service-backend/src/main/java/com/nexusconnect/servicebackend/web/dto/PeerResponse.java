package com.nexusconnect.servicebackend.web.dto;

public record PeerResponse(String user, String ip, int fileTcp, int voiceUdp, boolean viaNio) {}
