package com.nexusconnect.servicebackend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Captures audio from the microphone and streams it via UDP to a remote peer.
 * This thread runs independently and can be stopped via a flag.
 * 
 * Handles:
 * - Microphone audio capture using javax.sound.sampled
 * - Chunking audio into packets
 * - UDP transmission to target peer
 * - Thread-safe shutdown
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
public class AudioCaptureThread extends Thread {
    private static final Logger log = LoggerFactory.getLogger(AudioCaptureThread.class);

    private final String targetIp;
    private final int targetPort;
    private final AudioFormat audioFormat;
    private final int packetSizeBytes;
    private volatile boolean running = false;

    public AudioCaptureThread(String targetIp, int targetPort,
            int sampleRate, int bitsPerSample,
            int channels, int packetDurationMs) {
        super("AudioCapture-" + targetIp + ":" + targetPort);
        this.targetIp = targetIp;
        this.targetPort = targetPort;

        // Configure audio format: 16 kHz, 16-bit, Mono, signed PCM
        this.audioFormat = new AudioFormat(
                sampleRate, // Sample rate in Hz
                bitsPerSample, // Sample size in bits
                channels, // Number of channels
                true, // Signed
                false // Big-endian
        );

        // Calculate packet size: (sampleRate * durationMs / 1000) * (bits/8) * channels
        this.packetSizeBytes = (sampleRate * packetDurationMs / 1000) * (bitsPerSample / 8) * channels;

        setDaemon(false);
    }

    @Override
    public void run() {
        running = true;
        DatagramSocket socket = null;
        TargetDataLine line = null;

        try {
            // Create UDP socket for sending audio packets
            socket = new DatagramSocket();
            log.info("Audio capture thread started, will stream to {}:{}", targetIp, targetPort);

            // Get the target address
            InetAddress targetAddress = InetAddress.getByName(targetIp);

            // Open the audio input device
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(info)) {
                log.error("Audio format not supported by system");
                return;
            }

            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(audioFormat, packetSizeBytes * 2); // Buffer for ~2 packets
            line.start();
            log.info("Microphone audio line opened, format: {} Hz, {} bits, {} channels",
                    audioFormat.getSampleRate(),
                    audioFormat.getSampleSizeInBits(),
                    audioFormat.getChannels());

            // Prepare audio buffer
            byte[] audioBuffer = new byte[packetSizeBytes];

            // Capture and send audio packets
            while (running) {
                // Read audio data from the microphone
                int bytesRead = line.read(audioBuffer, 0, packetSizeBytes);

                if (bytesRead > 0) {
                    // Send the audio packet via UDP
                    DatagramPacket packet = new DatagramPacket(
                            audioBuffer,
                            bytesRead,
                            targetAddress,
                            targetPort);

                    try {
                        socket.send(packet);
                    } catch (IOException e) {
                        log.debug("Failed to send audio packet: {}", e.getMessage());
                        if (!running)
                            break; // Exit if shutdown was requested
                    }
                }
            }

        } catch (LineUnavailableException e) {
            log.error("Audio input line unavailable: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error in audio capture thread: {}", e.getMessage(), e);
        } finally {
            // Cleanup resources
            if (line != null) {
                line.stop();
                line.close();
                log.debug("Audio input line closed");
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log.debug("UDP socket closed");
            }
            log.info("Audio capture thread stopped");
        }
    }

    /**
     * Stops the audio capture thread gracefully.
     */
    public void stopCapture() {
        running = false;
        log.debug("Stopping audio capture to {}:{}", targetIp, targetPort);
    }

    /**
     * Checks if the capture thread is running.
     */
    public boolean isCapturing() {
        return running;
    }
}
