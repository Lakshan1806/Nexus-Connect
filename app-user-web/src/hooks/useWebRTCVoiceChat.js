import { useCallback, useEffect, useRef, useState } from 'react'

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080'
const WS_BASE = API_BASE.replace('http', 'ws')

/**
 * Custom React hook for WebRTC P2P voice chat with WebSocket signaling.
 * 
 * This is the NEW implementation using industry-standard WebRTC:
 * - WebSocket for instant signaling (offer/answer/ICE exchange)
 * - WebRTC PeerConnection for P2P audio streaming over UDP
 * - Custom Java STUN server for NAT traversal
 * 
 * Network Programming Concepts:
 * - WebSocket: Persistent TCP connection for signaling
 * - WebRTC: P2P UDP connections for media streaming
 * - STUN: UDP-based NAT traversal protocol
 * - ICE: Interactive Connectivity Establishment
 */
export function useWebRTCVoiceChat(apiBase = API_BASE) {
  const [status, setStatus] = useState('idle') // idle, connecting, connected, error
  const [error, setError] = useState('')
  const [currentSessionId, setCurrentSessionId] = useState(null)
  const [remotePeer, setRemotePeer] = useState(null)
  const [microphoneActive, setMicrophoneActive] = useState(true)
  const [incomingCalls, setIncomingCalls] = useState([])

  // WebSocket for signaling
  const signalingWsRef = useRef(null)
  const currentUserRef = useRef(null)

  // WebRTC references
  const peerConnectionRef = useRef(null)
  const localStreamRef = useRef(null)
  const remoteAudioRef = useRef(null)

  // ICE servers configuration
  const iceServersRef = useRef([
    { urls: `stun:localhost:3478` }, // Our custom STUN server
    { urls: 'stun:stun.l.google.com:19302' }, // Google STUN (fallback)
  ])

  /**
   * Connect to WebSocket signaling server
   */
  const connectSignaling = useCallback((username) => {
    if (signalingWsRef.current?.readyState === WebSocket.OPEN) {
      console.log('[WebRTC] Already connected to signaling server')
      return
    }

    console.log('[WebRTC] Connecting to signaling server as:', username)
    currentUserRef.current = username

    const ws = new WebSocket(`${WS_BASE}/ws/signaling?username=${encodeURIComponent(username)}`)

    ws.onopen = () => {
      console.log('✅ [WebRTC] Connected to signaling server')
    }

    ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data)
        console.log('📨 [WebRTC] Signaling message:', message.type, 'from:', message.from)
        handleSignalingMessage(message)
      } catch (err) {
        console.error('[WebRTC] Failed to parse signaling message:', err)
      }
    }

    ws.onerror = (err) => {
      console.error('❌ [WebRTC] Signaling WebSocket error:', err)
      setError('Signaling connection error')
    }

    ws.onclose = () => {
      console.log('[WebRTC] Signaling WebSocket closed')
      signalingWsRef.current = null
    }

    signalingWsRef.current = ws
  }, [])

  /**
   * Disconnect from signaling server
   */
  const disconnectSignaling = useCallback(() => {
    if (signalingWsRef.current) {
      signalingWsRef.current.close()
      signalingWsRef.current = null
    }
  }, [])

  /**
   * Send a message through the signaling WebSocket
   */
  const sendSignalingMessage = useCallback((message) => {
    if (signalingWsRef.current?.readyState === WebSocket.OPEN) {
      signalingWsRef.current.send(JSON.stringify(message))
      console.log('📤 [WebRTC] Sent signaling message:', message.type, 'to:', message.to)
    } else {
      console.error('[WebRTC] Cannot send message, signaling not connected')
    }
  }, [])

  /**
   * Handle incoming signaling messages
   */
  const handleSignalingMessage = useCallback(async (message) => {
    switch (message.type) {
      case 'connected':
        console.log('✅ [WebRTC] Signaling server confirmed connection')
        break

      case 'incoming-call':
        console.log('📞 [WebRTC] Incoming call from:', message.from)
        setIncomingCalls((prev) => [
          ...prev,
          {
            sessionId: message.data.sessionId,
            caller: message.from,
            timestamp: Date.now(),
          },
        ])
        break

      case 'call-accepted':
        console.log('✅ [WebRTC] Call accepted by:', message.from)
        break

      case 'offer':
        console.log('📥 [WebRTC] Received WebRTC offer from:', message.from)
        await handleOffer(message.from, message.data)
        break

      case 'answer':
        console.log('📥 [WebRTC] Received WebRTC answer from:', message.from)
        await handleAnswer(message.data)
        break

      case 'ice-candidate':
        console.log('🧊 [WebRTC] Received ICE candidate from:', message.from)
        await handleIceCandidate(message.data)
        break

      case 'call-ended':
        console.log('📴 [WebRTC] Call ended by:', message.from)
        endCall()
        break

      case 'call-rejected':
        console.log('❌ [WebRTC] Call rejected by:', message.from)
        setError('Call was rejected')
        setStatus('idle')
        break

      case 'peer-disconnected':
        console.log('📴 [WebRTC] Peer disconnected:', message.data.peer)
        endCall()
        break

      case 'error':
        console.error('❌ [WebRTC] Error from server:', message.data.message)
        setError(message.data.message)
        break

      default:
        console.warn('[WebRTC] Unknown message type:', message.type)
    }
  }, [])

  /**
   * Initialize WebRTC peer connection
   */
  const createPeerConnection = useCallback(() => {
    if (peerConnectionRef.current) {
      console.log('[WebRTC] Peer connection already exists')
      return peerConnectionRef.current
    }

    console.log('[WebRTC] Creating new RTCPeerConnection with ICE servers:', iceServersRef.current)

    const pc = new RTCPeerConnection({
      iceServers: iceServersRef.current,
    })

    // Handle ICE candidates
    pc.onicecandidate = (event) => {
      if (event.candidate) {
        console.log('🧊 [WebRTC] New ICE candidate generated')
        sendSignalingMessage({
          from: currentUserRef.current,
          to: remotePeer,
          type: 'ice-candidate',
          data: {
            candidate: event.candidate.candidate,
            sdpMLineIndex: event.candidate.sdpMLineIndex,
            sdpMid: event.candidate.sdpMid,
          },
        })
      } else {
        console.log('[WebRTC] All ICE candidates have been sent')
      }
    }

    // Handle connection state changes
    pc.onconnectionstatechange = () => {
      console.log('[WebRTC] Connection state:', pc.connectionState)
      if (pc.connectionState === 'connected') {
        console.log('✅ [WebRTC] Peer connection established!')
        setStatus('connected')
      } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        console.error('❌ [WebRTC] Connection failed or disconnected')
        setStatus('error')
        setError('Connection failed')
      }
    }

    // Handle ICE connection state
    pc.oniceconnectionstatechange = () => {
      console.log('[WebRTC] ICE connection state:', pc.iceConnectionState)
    }

    // Handle remote audio track
    pc.ontrack = (event) => {
      console.log('🎵 [WebRTC] Remote track received:', event.track.kind)
      if (event.track.kind === 'audio' && event.streams[0]) {
        console.log('[WebRTC] Setting remote audio stream')
        if (remoteAudioRef.current) {
          remoteAudioRef.current.srcObject = event.streams[0]
          remoteAudioRef.current.play().catch((err) => {
            console.error('[WebRTC] Error playing remote audio:', err)
          })
        }
      }
    }

    peerConnectionRef.current = pc
    return pc
  }, [remotePeer, sendSignalingMessage])

  /**
   * Get local microphone stream
   */
  const getLocalStream = useCallback(async () => {
    if (localStreamRef.current) {
      return localStreamRef.current
    }

    console.log('[WebRTC] 🎤 Requesting microphone access...')

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      })

      console.log('✅ [WebRTC] Microphone access granted')
      localStreamRef.current = stream
      return stream
    } catch (err) {
      console.error('❌ [WebRTC] Microphone access denied:', err)
      setError('Microphone access denied')
      throw err
    }
  }, [])

  /**
   * Initiate a call to another user
   */
  const initiateCall = useCallback(
    async (targetUser) => {
      if (!currentUserRef.current) {
        setError('Not connected to signaling server')
        return
      }

      console.log('[WebRTC] 📞 Initiating call to:', targetUser)
      setStatus('connecting')
      setRemotePeer(targetUser)

      try {
        // 1. Get local microphone
        const stream = await getLocalStream()

        // 2. Create peer connection
        const pc = createPeerConnection()

        // 3. Add local audio tracks
        stream.getTracks().forEach((track) => {
          pc.addTrack(track, stream)
          console.log('[WebRTC] Added local track:', track.kind)
        })

        // 4. Create offer
        console.log('[WebRTC] Creating WebRTC offer...')
        const offer = await pc.createOffer({
          offerToReceiveAudio: true,
          offerToReceiveVideo: false,
        })

        // 5. Set local description
        await pc.setLocalDescription(offer)
        console.log('[WebRTC] Local description set')

        // 6. Send offer through signaling
        sendSignalingMessage({
          from: currentUserRef.current,
          to: targetUser,
          type: 'offer',
          data: {
            sdp: offer.sdp,
            type: offer.type,
          },
        })

        setMicrophoneActive(true)
        console.log('[WebRTC] ✅ Call initiated successfully')
      } catch (err) {
        console.error('[WebRTC] Failed to initiate call:', err)
        setError('Failed to initiate call: ' + err.message)
        setStatus('error')
      }
    },
    [getLocalStream, createPeerConnection, sendSignalingMessage]
  )

  /**
   * Handle incoming offer
   */
  const handleOffer = useCallback(
    async (fromUser, data) => {
      console.log('[WebRTC] Handling offer from:', fromUser)
      setRemotePeer(fromUser)
      setStatus('connecting')

      try {
        // 1. Get local stream
        const stream = await getLocalStream()

        // 2. Create peer connection
        const pc = createPeerConnection()

        // 3. Add local tracks
        stream.getTracks().forEach((track) => {
          pc.addTrack(track, stream)
        })

        // 4. Set remote description (the offer)
        await pc.setRemoteDescription(
          new RTCSessionDescription({
            type: data.type,
            sdp: data.sdp,
          })
        )
        console.log('[WebRTC] Remote description (offer) set')

        // 5. Create answer
        const answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        console.log('[WebRTC] Answer created and local description set')

        // 6. Send answer through signaling
        sendSignalingMessage({
          from: currentUserRef.current,
          to: fromUser,
          type: 'answer',
          data: {
            sdp: answer.sdp,
            type: answer.type,
            sessionId: data.sessionId,
          },
        })

        setMicrophoneActive(true)
        console.log('[WebRTC] ✅ Answer sent')
      } catch (err) {
        console.error('[WebRTC] Failed to handle offer:', err)
        setError('Failed to accept call: ' + err.message)
        setStatus('error')
      }
    },
    [getLocalStream, createPeerConnection, sendSignalingMessage]
  )

  /**
   * Handle incoming answer
   */
  const handleAnswer = useCallback(async (data) => {
    if (!peerConnectionRef.current) {
      console.error('[WebRTC] No peer connection for answer')
      return
    }

    try {
      console.log('[WebRTC] Setting remote description (answer)...')
      await peerConnectionRef.current.setRemoteDescription(
        new RTCSessionDescription({
          type: data.type,
          sdp: data.sdp,
        })
      )
      console.log('[WebRTC] ✅ Remote description (answer) set')
      setCurrentSessionId(data.sessionId)
    } catch (err) {
      console.error('[WebRTC] Failed to set remote description:', err)
      setError('Failed to complete call setup: ' + err.message)
    }
  }, [])

  /**
   * Handle incoming ICE candidate
   */
  const handleIceCandidate = useCallback(async (data) => {
    if (!peerConnectionRef.current) {
      console.error('[WebRTC] No peer connection for ICE candidate')
      return
    }

    try {
      await peerConnectionRef.current.addIceCandidate(
        new RTCIceCandidate({
          candidate: data.candidate,
          sdpMLineIndex: data.sdpMLineIndex,
          sdpMid: data.sdpMid,
        })
      )
      console.log('[WebRTC] ✅ ICE candidate added')
    } catch (err) {
      console.error('[WebRTC] Failed to add ICE candidate:', err)
    }
  }, [])

  /**
   * Accept an incoming call
   */
  const acceptCall = useCallback(
    async (call) => {
      console.log('[WebRTC] Accepting call from:', call.caller)

      // Remove from incoming calls
      setIncomingCalls((prev) => prev.filter((c) => c.sessionId !== call.sessionId))

      // Send acceptance through signaling
      sendSignalingMessage({
        from: currentUserRef.current,
        to: call.caller,
        type: 'call-accept',
        data: {
          sessionId: call.sessionId,
        },
      })

      setCurrentSessionId(call.sessionId)
      // The offer will come through the 'offer' message
    },
    [sendSignalingMessage]
  )

  /**
   * Reject an incoming call
   */
  const rejectCall = useCallback(
    (call) => {
      console.log('[WebRTC] Rejecting call from:', call.caller)

      sendSignalingMessage({
        from: currentUserRef.current,
        to: call.caller,
        type: 'call-reject',
        data: {
          sessionId: call.sessionId,
        },
      })

      setIncomingCalls((prev) => prev.filter((c) => c.sessionId !== call.sessionId))
    },
    [sendSignalingMessage]
  )

  /**
   * End the current call
   */
  const endCall = useCallback(() => {
    console.log('[WebRTC] Ending call...')

    // Notify peer through signaling
    if (remotePeer && currentSessionId) {
      sendSignalingMessage({
        from: currentUserRef.current,
        to: remotePeer,
        type: 'call-end',
        data: {
          sessionId: currentSessionId,
        },
      })
    }

    // Close peer connection
    if (peerConnectionRef.current) {
      peerConnectionRef.current.close()
      peerConnectionRef.current = null
    }

    // Stop local stream
    if (localStreamRef.current) {
      localStreamRef.current.getTracks().forEach((track) => track.stop())
      localStreamRef.current = null
    }

    // Reset state
    setStatus('idle')
    setRemotePeer(null)
    setCurrentSessionId(null)
    setMicrophoneActive(false)
    setError('')

    console.log('[WebRTC] ✅ Call ended')
  }, [remotePeer, currentSessionId, sendSignalingMessage])

  /**
   * Toggle microphone on/off
   */
  const toggleMicrophone = useCallback(() => {
    if (localStreamRef.current) {
      localStreamRef.current.getAudioTracks().forEach((track) => {
        track.enabled = !track.enabled
      })
      setMicrophoneActive((prev) => !prev)
      console.log('[WebRTC] Microphone:', !microphoneActive ? 'enabled' : 'disabled')
    }
  }, [microphoneActive])

  /**
   * Set remote audio element
   */
  const setRemoteAudioElement = useCallback((element) => {
    console.log('[WebRTC] Setting remote audio element')
    remoteAudioRef.current = element
  }, [])

  /**
   * Cleanup on unmount
   */
  useEffect(() => {
    return () => {
      disconnectSignaling()
      endCall()
    }
  }, [disconnectSignaling, endCall])

  return {
    // State
    status,
    error,
    remotePeer,
    microphoneActive,
    incomingCalls,
    currentSessionId,

    // Methods
    connectSignaling,
    disconnectSignaling,
    initiateCall,
    acceptCall,
    rejectCall,
    endCall,
    toggleMicrophone,
    setRemoteAudioElement,

    // Computed
    isConnected: status === 'connected',
    isConnecting: status === 'connecting',
    isIdle: status === 'idle',
    hasError: status === 'error',
  }
}
