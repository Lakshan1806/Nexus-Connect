package com.nexusconnect.servicebackend.web.dto;

/**
 * Configuration for audio streaming parameters.
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
public record AudioStreamConfig(
        int sampleRate, // e.g., 16000 Hz
        int channels, // 1 (mono) or 2 (stereo)
        int bitsPerSample, // 16 bits per sample
        int packetDurationMs // Duration of audio per packet (e.g., 20ms)
) {
    public static AudioStreamConfig createDefault() {
        return new AudioStreamConfig(
                16000, // 16 kHz sample rate (sufficient for voice)
                1, // Mono channel
                16, // 16-bit samples
                20 // 20ms per packet (standard for VoIP)
        );
    }

    public int getBytesPerPacket() {
        return (sampleRate * packetDurationMs / 1000) * (bitsPerSample / 8) * channels;
    }
}
