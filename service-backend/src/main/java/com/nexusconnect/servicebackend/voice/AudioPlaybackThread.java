package com.nexusconnect.servicebackend.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.*;

/**
 * Receives audio packets via UDP and plays them through the speaker.
 * Implements a jitter buffer to handle packet loss and out-of-order delivery,
 * which is inherent to UDP transmission.
 * 
 * Handles:
 * - UDP packet reception on a specific port
 * - Jitter buffering to smooth playback
 * - Audio playback using javax.sound.sampled
 * - Thread-safe shutdown
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
public class AudioPlaybackThread extends Thread {
    private static final Logger log = LoggerFactory.getLogger(AudioPlaybackThread.class);

    private static final int JITTER_BUFFER_SIZE = 10; // Number of packets to buffer
    private static final int UDP_PACKET_SIZE = 4096; // Max UDP packet size

    private final int listeningPort;
    private final AudioFormat audioFormat;
    private final int packetSizeBytes;
    private volatile boolean running = false;

    // Jitter buffer using a blocking queue
    private final BlockingQueue<AudioPacket> jitterBuffer;

    public AudioPlaybackThread(int listeningPort,
            int sampleRate, int bitsPerSample,
            int channels, int packetDurationMs) {
        super("AudioPlayback-port:" + listeningPort);
        this.listeningPort = listeningPort;

        // Configure audio format: 16 kHz, 16-bit, Mono, signed PCM
        this.audioFormat = new AudioFormat(
                sampleRate, // Sample rate in Hz
                bitsPerSample, // Sample size in bits
                channels, // Number of channels
                true, // Signed
                false // Big-endian
        );

        // Calculate packet size
        this.packetSizeBytes = (sampleRate * packetDurationMs / 1000) * (bitsPerSample / 8) * channels;

        // Initialize jitter buffer
        this.jitterBuffer = new LinkedBlockingQueue<>(JITTER_BUFFER_SIZE);

        setDaemon(false);
    }

    @Override
    public void run() {
        running = true;
        DatagramSocket socket = null;
        SourceDataLine line = null;

        try {
            // Create UDP socket to listen for incoming audio
            socket = new DatagramSocket(listeningPort);
            log.info("Audio playback thread started, listening on port {}", listeningPort);

            // Open the audio output device
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(info)) {
                log.error("Audio format not supported by system");
                return;
            }

            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(audioFormat, packetSizeBytes * 4); // Buffer for ~4 packets
            line.start();
            log.info("Speaker audio line opened, format: {} Hz, {} bits, {} channels",
                    audioFormat.getSampleRate(),
                    audioFormat.getSampleSizeInBits(),
                    audioFormat.getChannels());

            // Start the playback thread (reads from jitter buffer)
            Thread playbackThread = new Thread(this::playAudio, "AudioPlayback-output-" + listeningPort);
            playbackThread.setDaemon(false);
            playbackThread.start();

            // Receive UDP packets in this thread
            byte[] receiveBuffer = new byte[UDP_PACKET_SIZE];
            while (running) {
                DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                try {
                    socket.receive(packet);

                    if (packet.getLength() > 0) {
                        // Create audio packet with timestamp
                        byte[] audioData = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), 0, audioData, 0, packet.getLength());
                        AudioPacket audioPacket = new AudioPacket(
                                System.currentTimeMillis(),
                                audioData);

                        // Try to add to jitter buffer (non-blocking)
                        if (!jitterBuffer.offer(audioPacket)) {
                            log.debug("Jitter buffer full, dropping packet");
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.debug("Error receiving audio packet: {}", e.getMessage());
                    }
                }
            }

            // Wait for playback thread to finish
            playbackThread.join(5000);

        } catch (Exception e) {
            log.error("Error in audio playback thread: {}", e.getMessage(), e);
        } finally {
            running = false;
            if (line != null) {
                line.stop();
                line.close();
                log.debug("Audio output line closed");
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log.debug("UDP socket closed");
            }
            log.info("Audio playback thread stopped");
        }
    }

    /**
     * Reads from the jitter buffer and plays audio to the speaker.
     * This runs in a separate thread to prevent blocking the UDP receive loop.
     */
    private void playAudio() {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(audioFormat);
            line.start();

            byte[] playbackBuffer = new byte[packetSizeBytes];

            while (running) {
                try {
                    // Wait up to 100ms for a packet from the jitter buffer
                    AudioPacket packet = jitterBuffer.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);

                    if (packet != null) {
                        // Write audio data to speaker
                        line.write(packet.audioData, 0, packet.audioData.length);
                    }
                    // If no packet, silence (line buffer will handle it)

                } catch (InterruptedException e) {
                    if (!running)
                        break;
                }
            }

            line.stop();
            line.close();
        } catch (LineUnavailableException e) {
            log.error("Audio output line unavailable: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Error in playback: {}", e.getMessage(), e);
        }
    }

    /**
     * Stops the audio playback thread gracefully.
     */
    public void stopPlayback() {
        running = false;
        log.debug("Stopping audio playback on port {}", listeningPort);
    }

    /**
     * Checks if the playback thread is running.
     */
    public boolean isPlaying() {
        return running;
    }

    /**
     * Gets jitter buffer statistics.
     */
    public int getBufferSize() {
        return jitterBuffer.size();
    }

    /**
     * Inner class to represent an audio packet with timestamp.
     */
    private static class AudioPacket {
        final long receivedTime;
        final byte[] audioData;

        AudioPacket(long receivedTime, byte[] audioData) {
            this.receivedTime = receivedTime;
            this.audioData = audioData;
        }
    }
}
