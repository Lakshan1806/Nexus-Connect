/**
 * React Hook for WebSocket Voice Relay
 *
 * This hook manages the WebSocket connection for voice streaming
 * and provides methods to send/receive audio data.
 *
 * Usage:
 *
 * const {
 *   isConnected,
 *   connect,
 *   disconnect,
 *   sendAudio,
 *   onAudioReceived
 * } = useVoiceWebSocket('alice');
 *
 * // Connect when component mounts
 * useEffect(() => {
 *   connect();
 *   return () => disconnect();
 * }, []);
 *
 * // Handle incoming audio
 * useEffect(() => {
 *   onAudioReceived((audioData) => {
 *     playAudio(audioData);
 *   });
 * }, []);
 */

import { useState, useRef, useCallback, useEffect } from "react";

const WEBSOCKET_URL = "ws://localhost:8080/ws/voice";

export function useVoiceWebSocket(username) {
  const [isConnected, setIsConnected] = useState(false);
  const wsRef = useRef(null);
  const audioCallbackRef = useRef(null);

  /**
   * Connect to the WebSocket server
   */
  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      console.log("WebSocket already connected");
      return;
    }

    const url = `${WEBSOCKET_URL}?username=${encodeURIComponent(username)}`;
    console.log(`Connecting to voice WebSocket: ${url}`);

    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";

    ws.onopen = () => {
      console.log("Voice WebSocket connected");
      setIsConnected(true);
    };

    ws.onmessage = (event) => {
      // Received audio from peer
      const audioData = new Uint8Array(event.data);
      console.log(`Received ${audioData.length} bytes of audio`);

      if (audioCallbackRef.current) {
        audioCallbackRef.current(audioData);
      }
    };

    ws.onerror = (error) => {
      console.error("Voice WebSocket error:", error);
    };

    ws.onclose = (event) => {
      console.log("Voice WebSocket disconnected:", event.code, event.reason);
      setIsConnected(false);
    };

    wsRef.current = ws;
  }, [username]);

  /**
   * Disconnect from the WebSocket server
   */
  const disconnect = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
      setIsConnected(false);
    }
  }, []);

  /**
   * Send audio data to the server (will be relayed to peer)
   * @param {ArrayBuffer|TypedArray} audioData - Raw audio data
   */
  const sendAudio = useCallback((audioData) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(audioData);
    } else {
      console.warn("Cannot send audio: WebSocket not connected");
    }
  }, []);

  /**
   * Register a callback for when audio is received from peer
   * @param {Function} callback - Called with Uint8Array of audio data
   */
  const onAudioReceived = useCallback((callback) => {
    audioCallbackRef.current = callback;
  }, []);

  /**
   * Get current connection state
   */
  const getState = useCallback(() => {
    return wsRef.current?.readyState || WebSocket.CLOSED;
  }, []);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      disconnect();
    };
  }, [disconnect]);

  return {
    isConnected,
    connect,
    disconnect,
    sendAudio,
    onAudioReceived,
    getState,
  };
}

/**
 * React Hook for Voice Relay API calls
 *
 * Provides methods to initiate, accept, reject, and end calls.
 */
export function useVoiceRelayAPI() {
  const API_BASE = "/api/voice-relay";

  const initiateCall = async (initiator, target) => {
    const response = await fetch(`${API_BASE}/initiate`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ initiator, target }),
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.message || "Failed to initiate call");
    }

    return response.json();
  };

  const acceptCall = async (sessionId, accepter) => {
    const response = await fetch(
      `${API_BASE}/accept/${sessionId}?accepter=${accepter}`,
      {
        method: "POST",
      }
    );

    if (!response.ok) {
      const error = await response.text();
      throw new Error(error || "Failed to accept call");
    }

    return response.text();
  };

  const rejectCall = async (sessionId, rejector) => {
    const response = await fetch(
      `${API_BASE}/reject/${sessionId}?rejector=${rejector}`,
      {
        method: "POST",
      }
    );

    if (!response.ok) {
      const error = await response.text();
      throw new Error(error || "Failed to reject call");
    }

    return response.text();
  };

  const endCall = async (sessionId) => {
    const response = await fetch(`${API_BASE}/end/${sessionId}`, {
      method: "POST",
    });

    if (!response.ok) {
      const error = await response.text();
      throw new Error(error || "Failed to end call");
    }

    return response.text();
  };

  const getIncomingCalls = async (username) => {
    const response = await fetch(`${API_BASE}/incoming?username=${username}`);

    if (!response.ok) {
      throw new Error("Failed to fetch incoming calls");
    }

    return response.json();
  };

  const getUserSessions = async (username) => {
    const response = await fetch(`${API_BASE}/sessions?username=${username}`);

    if (!response.ok) {
      throw new Error("Failed to fetch user sessions");
    }

    return response.json();
  };

  const getConnectionStatus = async (username) => {
    const response = await fetch(`${API_BASE}/status?username=${username}`);

    if (!response.ok) {
      throw new Error("Failed to fetch connection status");
    }

    return response.json();
  };

  return {
    initiateCall,
    acceptCall,
    rejectCall,
    endCall,
    getIncomingCalls,
    getUserSessions,
    getConnectionStatus,
  };
}

/**
 * Example: Audio capture and processing
 *
 * This function shows how to capture microphone audio and convert it
 * to a format suitable for sending over WebSocket.
 */
export async function captureAndStreamAudio(sendAudioCallback) {
  try {
    // Request microphone access
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });

    const audioContext = new AudioContext({ sampleRate: 16000 }); // 16kHz for voice
    const source = audioContext.createMediaStreamSource(stream);
    const processor = audioContext.createScriptProcessor(4096, 1, 1);

    source.connect(processor);
    processor.connect(audioContext.destination);

    processor.onaudioprocess = (e) => {
      const inputData = e.inputBuffer.getChannelData(0);

      // Convert Float32Array to Int16Array (PCM)
      const pcmData = new Int16Array(inputData.length);
      for (let i = 0; i < inputData.length; i++) {
        const sample = Math.max(-1, Math.min(1, inputData[i]));
        pcmData[i] = sample * 0x7fff;
      }

      // Send to server
      sendAudioCallback(pcmData.buffer);
    };

    return {
      stop: () => {
        processor.disconnect();
        source.disconnect();
        stream.getTracks().forEach((track) => track.stop());
        audioContext.close();
      },
    };
  } catch (error) {
    console.error("Error capturing audio:", error);
    throw error;
  }
}

/**
 * Example: Audio playback
 *
 * This function shows how to play received audio data.
 */
export class AudioPlayer {
  constructor(sampleRate = 16000) {
    this.audioContext = new AudioContext({ sampleRate });
    this.queue = [];
    this.isPlaying = false;
  }

  play(audioData) {
    // Convert Int16Array to Float32Array
    const int16Data = new Int16Array(audioData.buffer || audioData);
    const float32Data = new Float32Array(int16Data.length);

    for (let i = 0; i < int16Data.length; i++) {
      float32Data[i] = int16Data[i] / 0x7fff;
    }

    // Create audio buffer
    const audioBuffer = this.audioContext.createBuffer(
      1,
      float32Data.length,
      this.audioContext.sampleRate
    );
    audioBuffer.getChannelData(0).set(float32Data);

    // Create buffer source
    const source = this.audioContext.createBufferSource();
    source.buffer = audioBuffer;
    source.connect(this.audioContext.destination);
    source.start();
  }

  close() {
    this.audioContext.close();
  }
}
