import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080'
const AUDIO_BUFFER_TIME_MS = 50 // Time to buffer packets before playback

/**
 * Custom React hook for managing P2P voice chat sessions.
 * 
 * Handles:
 * - Voice session initiation via REST API
 * - Audio capture and playback thread coordination
 * - Connection status tracking
 * - Error handling and recovery
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
export function useVoiceChat(apiBase = API_BASE) {
  const [sessionId, setSessionId] = useState(null)
  const [status, setStatus] = useState('idle') // idle, connecting, connected, error
  const [error, setError] = useState('')
  const [remotePeerInfo, setRemotePeerInfo] = useState(null)
  const [microphoneActive, setMicrophoneActive] = useState(false)
  const [audioConfig, setAudioConfig] = useState(null)

  const sessionRef = useRef(sessionId)
  const audioWorkerRef = useRef(null)
  const mediaStreamRef = useRef(null)
  const mediaStreamAudioContextRef = useRef(null)

  // Update session ref whenever it changes
  useEffect(() => {
    sessionRef.current = sessionId
  }, [sessionId])

  /**
   * Fetch audio configuration from server
   */
  const fetchAudioConfig = useCallback(async () => {
    try {
      const response = await axios.get(`${apiBase}/api/voice/config`)
      setAudioConfig(response.data)
      return response.data
    } catch (err) {
      console.error('Failed to fetch audio config:', err)
      // Use defaults if server doesn't respond
      return {
        sampleRate: 16000,
        channels: 1,
        bitsPerSample: 16,
        packetDurationMs: 20,
      }
    }
  }, [apiBase])

  /**
   * Initiates a voice session with a target peer
   */
  const initiateVoiceCall = useCallback(
    async (currentUser, targetUser, localUdpPort, peerDetails) => {
      if (!currentUser || !targetUser || !localUdpPort) {
        setError('Missing required information')
        setStatus('error')
        return null
      }

      try {
        setStatus('connecting')
        setError('')

        const response = await axios.post(`${apiBase}/api/voice/initiate`, {
          initiator: currentUser,
          target: targetUser,
          localUdpPort: localUdpPort,
        })

        const { success, message, targetIp, targetPort, sessionId: newSessionId } = response.data

        if (!success) {
          setError(message || 'Failed to initiate voice call')
          setStatus('error')
          return null
        }

        // Store remote peer info
        setRemotePeerInfo({
          username: targetUser,
          ip: targetIp,
          port: targetPort,
        })

        setSessionId(newSessionId)
        setStatus('connected')

        // Fetch audio configuration
        const config = await fetchAudioConfig()
        setAudioConfig(config)

        // Start capturing audio from microphone
        await startAudioCapture(targetIp, targetPort, config)

        // Start listening for incoming audio
        await startAudioPlayback(localUdpPort, config)

        return newSessionId
      } catch (err) {
        const errorMessage = err.response?.data?.message || err.message || 'Voice call initiation failed'
        setError(errorMessage)
        setStatus('error')
        console.error('Voice call initiation error:', err)
        return null
      }
    },
    [apiBase, fetchAudioConfig]
  )

  /**
   * Starts capturing audio from microphone
   * Note: In a real implementation, this would use WebRTC or MediaRecorder
   */
  const startAudioCapture = useCallback(async (targetIp, targetPort, config) => {
    try {
      // Note: Web browsers have security restrictions on raw UDP sockets
      // This would typically be handled by:
      // 1. A WebRTC connection (preferred)
      // 2. A Websocket bridge to a local Java client
      // 3. A worker thread in a desktop app

      // For this demonstration, we'll prepare the setup:
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      mediaStreamRef.current = stream
      setMicrophoneActive(true)

      // In a desktop app (JavaFX/Swing with Java backend), this would:
      // - Create AudioCaptureThread pointed at targetIp:targetPort
      // - Start capturing microphone audio
      // - Chunk and send packets via UDP

      console.log(`Audio capture would start to ${targetIp}:${targetPort}`)
    } catch (err) {
      console.error('Failed to start audio capture:', err)
      throw err
    }
  }, [])

  /**
   * Starts listening for incoming audio
   * Note: In a real implementation, this would use WebRTC or a bridge
   */
  const startAudioPlayback = useCallback(async (localPort, config) => {
    try {
      // In a desktop app (JavaFX/Swing with Java backend), this would:
      // - Create AudioPlaybackThread listening on localPort
      // - Start receiving UDP packets
      // - Buffer and play audio with jitter handling

      console.log(`Audio playback would start listening on port ${localPort}`)
    } catch (err) {
      console.error('Failed to start audio playback:', err)
      throw err
    }
  }, [])

  /**
   * Terminates the current voice session
   */
  const endVoiceCall = useCallback(async () => {
    if (!sessionRef.current) {
      return
    }

    try {
      // Notify server to terminate session
      await axios.post(`${apiBase}/api/voice/terminate/${sessionRef.current}`)
    } catch (err) {
      console.error('Error terminating voice session:', err)
    } finally {
      // Stop audio streams
      if (mediaStreamRef.current) {
        mediaStreamRef.current.getTracks().forEach((track) => track.stop())
        mediaStreamRef.current = null
      }

      if (mediaStreamAudioContextRef.current) {
        mediaStreamAudioContextRef.current.close()
        mediaStreamAudioContextRef.current = null
      }

      // Reset state
      setSessionId(null)
      setStatus('idle')
      setError('')
      setRemotePeerInfo(null)
      setMicrophoneActive(false)
    }
  }, [apiBase])

  /**
   * Gets current session status
   */
  const getSessionStatus = useCallback(async () => {
    if (!sessionRef.current) {
      return null
    }

    try {
      const response = await axios.get(`${apiBase}/api/voice/status/${sessionRef.current}`)
      return response.data
    } catch (err) {
      console.error('Failed to get session status:', err)
      return null
    }
  }, [apiBase])

  /**
   * Enables/disables microphone
   */
  const toggleMicrophone = useCallback(async () => {
    if (mediaStreamRef.current) {
      mediaStreamRef.current.getAudioTracks().forEach((track) => {
        track.enabled = !track.enabled
      })
      setMicrophoneActive(!microphoneActive)
    }
  }, [microphoneActive])

  return {
    // State
    sessionId,
    status,
    error,
    remotePeerInfo,
    microphoneActive,
    audioConfig,

    // Methods
    initiateVoiceCall,
    endVoiceCall,
    toggleMicrophone,
    getSessionStatus,
    fetchAudioConfig,

    // Utils
    isConnected: status === 'connected',
    isConnecting: status === 'connecting',
    hasError: status === 'error',
  }
}
