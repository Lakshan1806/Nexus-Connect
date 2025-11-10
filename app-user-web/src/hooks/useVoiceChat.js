import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080'

/**
 * Custom React hook for managing P2P WebRTC voice chat sessions.
 * 
 * Handles:
 * - WebRTC peer connection setup with REST API signaling
 * - Audio capture and playback via MediaStream API (WebRTC uses UDP internally)
 * - Connection status tracking
 * - Error handling and recovery
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP via WebRTC)
 * 
 * Architecture:
 * 1. REST API for signaling (session initiation, peer discovery)
 * 2. WebRTC PeerConnection for P2P media (uses UDP internally)
 * 3. MediaStream API for audio capture/playback
 */
export function useVoiceChat(apiBase = API_BASE) {
  const [sessionId, setSessionId] = useState(null)
  const [status, setStatus] = useState('idle') // idle, ringing, connecting, connected, error
  const [error, setError] = useState('')
  const [remotePeerInfo, setRemotePeerInfo] = useState(null)
  const [microphoneActive, setMicrophoneActive] = useState(true)
  const [audioConfig, setAudioConfig] = useState(null)
  const [incomingCalls, setIncomingCalls] = useState([])
  const [isCheckingCalls, setIsCheckingCalls] = useState(false)

  // WebRTC refs
  const peerConnectionRef = useRef(null)
  const sessionRef = useRef(sessionId)
  const mediaStreamRef = useRef(null)
  const remoteAudioRef = useRef(null)
  const remoteStreamRef = useRef(null)  // Store the actual remote stream separately
  const iceServersRef = useRef([
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' },
  ])

  // Update session ref whenever it changes
  useEffect(() => {
    sessionRef.current = sessionId
  }, [sessionId])

  // Poll session status when connected to detect if other user ended the call
  useEffect(() => {
    if (status !== 'connected' || !sessionRef.current) {
      return
    }

    const currentSessionId = sessionRef.current
    console.log('[VoiceChat] Starting session status polling for session:', currentSessionId)
    
    const pollInterval = setInterval(async () => {
      try {
        const response = await axios.get(`${apiBase}/api/voice/status/${currentSessionId}`)
        const sessionStatus = response.data
        
        // If session is ENDED, the other user hung up
        if (sessionStatus.state === 'ENDED') {
          console.log('[VoiceChat] 🔴 Remote user ended the call, cleaning up...')
          clearInterval(pollInterval)
          
          // Clean up WebRTC
          if (peerConnectionRef.current) {
            peerConnectionRef.current.close()
            peerConnectionRef.current = null
          }

          // Stop audio streams
          if (mediaStreamRef.current) {
            mediaStreamRef.current.getTracks().forEach((track) => {
              track.stop()
            })
            mediaStreamRef.current = null
          }

          // Reset state
          setSessionId(null)
          setStatus('idle')
          setRemotePeerInfo(null)
        }
      } catch (err) {
        console.error('[VoiceChat] Failed to poll session status:', err)
        // Session might be deleted, end the call
        clearInterval(pollInterval)
        setSessionId(null)
        setStatus('idle')
        setRemotePeerInfo(null)
      }
    }, 2000) // Poll every 2 seconds

    return () => {
      console.log('[VoiceChat] Stopping session status polling')
      clearInterval(pollInterval)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]) // Only re-run when status changes to 'connected'

  /**
   * Fetch audio configuration from server
   */
  const fetchAudioConfig = useCallback(async () => {
    try {
      const response = await axios.get(`${apiBase}/api/voice/config`)
      setAudioConfig(response.data)
      return response.data
    } catch (err) {
      console.error('[VoiceChat] Failed to fetch audio config:', err)
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
   * Check for incoming calls (poll from server)
   */
  const checkIncomingCalls = useCallback(async (currentUser) => {
    if (!currentUser || isCheckingCalls) {
      return
    }
    
    try {
      setIsCheckingCalls(true)
      const response = await axios.get(`${apiBase}/api/voice/incoming`, {
        params: { user: currentUser }
      })
      const calls = response.data || []
      if (calls.length > 0) {
        console.log(`[VoiceChat] ${calls.length} incoming call(s) for ${currentUser}:`, calls)
      }
      setIncomingCalls(calls)
    } catch (err) {
      console.error('[VoiceChat] Failed to check incoming calls:', err)
    } finally {
      setIsCheckingCalls(false)
    }
  }, [apiBase, isCheckingCalls])

  /**
   * Initialize WebRTC PeerConnection
   */
  const initializePeerConnection = useCallback(async () => {
    try {
      const peerConnection = new RTCPeerConnection({
        iceServers: iceServersRef.current,
        bundlePolicy: 'max-bundle',
        rtcpMuxPolicy: 'require',
      })

      // Handle ICE candidates
      peerConnection.onicecandidate = (event) => {
        if (event.candidate) {
          console.log('[VoiceChat] New ICE candidate:', event.candidate.candidate)
        } else {
          console.log('[VoiceChat] ICE candidate gathering complete')
        }
      }

      // Handle ICE gathering state
      peerConnection.onicegatheringstatechange = () => {
        console.log('[VoiceChat] ICE gathering state:', peerConnection.iceGatheringState)
      }

      // Handle connection state changes
      peerConnection.onconnectionstatechange = () => {
        console.log('[VoiceChat] Connection state:', peerConnection.connectionState)
        switch (peerConnection.connectionState) {
          case 'connected':
            console.log('[VoiceChat] Peer connection established')
            setStatus('connected')
            break
          case 'disconnected':
            console.log('[VoiceChat] Connection disconnected')
            break
          case 'failed':
            console.error('[VoiceChat] Connection failed')
            setError('Connection failed')
            setStatus('error')
            break
          case 'closed':
            console.log('[VoiceChat] Peer connection closed')
            break
        }
      }

      // Handle ICE connection state
      peerConnection.oniceconnectionstatechange = () => {
        console.log('[VoiceChat] ICE state:', peerConnection.iceConnectionState)
        if (peerConnection.iceConnectionState === 'connected' || peerConnection.iceConnectionState === 'completed') {
          setStatus('connected')
        }
      }

      // Handle signaling state changes
      peerConnection.onsignalingstatechange = () => {
        console.log('[VoiceChat] Signaling state:', peerConnection.signalingState)
      }

      // Handle remote streams
      peerConnection.ontrack = (event) => {
        console.log('[VoiceChat] 🎵 Remote track received:', event.track.kind, 'Streams:', event.streams.length)
        
        if (event.track.kind === 'audio' && event.streams && event.streams.length > 0) {
          const remoteStream = event.streams[0]
          const remoteTrack = event.track
          
          console.log('[VoiceChat] Remote audio stream:', remoteStream.id, 'Tracks:', remoteStream.getTracks().length)
          console.log('[VoiceChat] Stream active:', remoteStream.active)
          
          // Log track details
          remoteStream.getAudioTracks().forEach((track, i) => {
            console.log(`[VoiceChat] Audio track ${i}:`, {
              id: track.id,
              label: track.label,
              enabled: track.enabled,
              muted: track.muted,
              readyState: track.readyState
            })
            
            // ⚠️ CRITICAL: Listen for track ending
            track.onended = () => {
              console.error('[VoiceChat] ❌❌❌ REMOTE TRACK ENDED! This is why audio stopped!')
            }
            
            track.onmute = () => {
              console.warn('[VoiceChat] ⚠️ Remote track muted')
            }
            
            track.onunmute = () => {
              console.log('[VoiceChat] ✅ Remote track unmuted')
              console.log('[VoiceChat] Track after unmute:', {
                id: track.id,
                enabled: track.enabled,
                muted: track.muted,
                readyState: track.readyState
              })
            }
          })
          
          // Store the remote stream for later use
          remoteStreamRef.current = remoteStream
          
          // If we have an audio element ref, connect it
          if (remoteAudioRef.current) {
            console.log('[VoiceChat] ✅ Audio element ref exists, connecting stream...')
            console.log('[VoiceChat] Audio element before:', {
              paused: remoteAudioRef.current.paused,
              muted: remoteAudioRef.current.muted,
              volume: remoteAudioRef.current.volume,
              srcObject: remoteAudioRef.current.srcObject
            })
            
            remoteAudioRef.current.srcObject = remoteStream
            
            console.log('[VoiceChat] Audio element after srcObject set:', {
              paused: remoteAudioRef.current.paused,
              muted: remoteAudioRef.current.muted,
              volume: remoteAudioRef.current.volume,
              srcObject: !!remoteAudioRef.current.srcObject
            })
            
            // Force play
            remoteAudioRef.current.play()
              .then(() => {
                console.log('[VoiceChat] ✅✅✅ AUDIO PLAY SUCCESSFUL! ✅✅✅')
              })
              .catch(err => {
                console.error('[VoiceChat] ❌ Autoplay blocked, needs user interaction:', err.message)
              })
          } else {
            console.warn('[VoiceChat] ⚠️ Audio element ref not available yet, stream stored for later')
          }
        }
      }

      peerConnectionRef.current = peerConnection
      return peerConnection
    } catch (err) {
      console.error('[VoiceChat] Failed to initialize peer connection:', err)
      throw err
    }
  }, [])

  /**
   * Get local media stream from microphone
   */
  const getLocalMediaStream = useCallback(async () => {
    try {
      console.log('[VoiceChat] 🎤 Requesting microphone access...')
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
        video: false,
      })

      console.log('[VoiceChat] ✅ Local media stream acquired')
      console.log('[VoiceChat] Stream ID:', stream.id)
      console.log('[VoiceChat] Stream active:', stream.active)
      console.log('[VoiceChat] Audio tracks:', stream.getAudioTracks().length)
      
      stream.getAudioTracks().forEach((track, i) => {
        console.log(`[VoiceChat] Local audio track ${i}:`, {
          id: track.id,
          label: track.label,
          enabled: track.enabled,
          muted: track.muted,
          readyState: track.readyState,
          settings: track.getSettings()
        })
      })
      
      mediaStreamRef.current = stream
      return stream
    } catch (err) {
      console.error('[VoiceChat] ❌ Failed to get local media stream:', err)
      setError('Microphone access denied')
      setStatus('error')
      throw err
    }
  }, [])

  /**
   * Add local audio tracks to peer connection
   */
  const addLocalAudioTracks = useCallback(async (peerConnection, mediaStream) => {
    try {
      console.log('[VoiceChat] 📤 Adding local audio tracks to peer connection...')
      mediaStream.getAudioTracks().forEach((track) => {
        const sender = peerConnection.addTrack(track, mediaStream)
        console.log('[VoiceChat] ✅ Local audio track added:', {
          trackId: track.id,
          trackLabel: track.label,
          trackEnabled: track.enabled,
          trackMuted: track.muted,
          trackReadyState: track.readyState,
          sender: !!sender
        })
        
        // Monitor local track for issues
        track.onended = () => {
          console.error('[VoiceChat] ❌❌❌ LOCAL TRACK ENDED! Microphone stopped!')
        }
        
        track.onmute = () => {
          console.warn('[VoiceChat] ⚠️ LOCAL track muted')
        }
        
        // Check track after 3 seconds
        setTimeout(() => {
          console.log('[VoiceChat] 🔍 LOCAL TRACK CHECK (3s after adding):', {
            id: track.id,
            enabled: track.enabled,
            muted: track.muted,
            readyState: track.readyState,
            streamActive: mediaStream.active
          })
        }, 3000)
      })
      
      console.log('[VoiceChat] Total senders:', peerConnection.getSenders().length)
    } catch (err) {
      console.error('[VoiceChat] ❌ Failed to add local audio tracks:', err)
      throw err
    }
  }, [])

  /**
   * Accept an incoming call from another user
   */
  const acceptIncomingCall = useCallback(async (sessionId, currentUser, localUdpPort) => {
    try {
      console.log(`[VoiceChat] Accepting call ${sessionId} as user ${currentUser}`)
      
      const response = await axios.post(`${apiBase}/api/voice/accept/${sessionId}`, {
        accepter: currentUser,
        localUdpPort: localUdpPort
      })
      
      const { state, initiator, initiatorIp, initiatorPort } = response.data
      
      if (state === 'CONNECTED') {
        // Store remote peer info (the caller)
        setRemotePeerInfo({
          username: initiator,
          ip: initiatorIp,
          port: initiatorPort,
        })
        
        setSessionId(sessionId)
        setStatus('connecting')
        
        // Initialize WebRTC and media
        const config = await fetchAudioConfig()
        setAudioConfig(config)
        
        const peerConnection = await initializePeerConnection()
        const mediaStream = await getLocalMediaStream()
        await addLocalAudioTracks(peerConnection, mediaStream)
        
        // CRITICAL: Retrieve the offer and send answer back
        console.log('[VoiceChat] Retrieving offer from initiator...')
        try {
          // Poll for the offer
          let offerReceived = false
          let pollAttempts = 0
          const maxAttempts = 30 // Wait up to 6 seconds
          
          while (!offerReceived && pollAttempts < maxAttempts) {
            try {
              const offerResponse = await axios.get(`${apiBase}/api/voice/sdp/offer/${sessionId}`)
              
              if (offerResponse.status === 200 && offerResponse.data.sdp) {
                console.log('[VoiceChat] Offer received from initiator!')
                
                // Set remote description with the offer
                const offerSdp = new RTCSessionDescription({
                  type: 'offer',
                  sdp: offerResponse.data.sdp
                })
                await peerConnection.setRemoteDescription(offerSdp)
                console.log('[VoiceChat] Remote offer SDP set successfully')
                
                // Create answer
                const answer = await peerConnection.createAnswer()
                await peerConnection.setLocalDescription(answer)
                console.log('[VoiceChat] Local answer SDP created and set')
                
                // Send answer back to the server
                await axios.post(`${apiBase}/api/voice/sdp/answer/${sessionId}`, {
                  sdp: answer.sdp
                })
                console.log('[VoiceChat] Answer sent to backend for initiator')
                
                offerReceived = true
              }
            } catch (pollErr) {
              // 204 means not ready yet, 404 means session not found
              if (pollErr.response?.status !== 204 && pollErr.response?.status !== 404) {
                console.warn('[VoiceChat] Error polling for offer:', pollErr.message)
              }
              
              // Wait a bit before next poll
              await new Promise(r => setTimeout(r, 200))
            }
            
            pollAttempts++
          }
          
          if (!offerReceived) {
            console.warn('[VoiceChat] Timeout waiting for offer from initiator')
            // Continue anyway - might receive late
          }
        } catch (sdpErr) {
          console.error('[VoiceChat] SDP exchange failed:', sdpErr)
          // Continue with basic connection anyway
        }
        
        setStatus('connected')
        setMicrophoneActive(true)
        setIncomingCalls([])  // Clear incoming calls after accepting
        
        console.log('[VoiceChat] Incoming call accepted and connected')
        return true
      }
    } catch (err) {
      console.error('[VoiceChat] Failed to accept call:', err)
      setError('Failed to accept call: ' + (err.response?.data?.error || err.message))
      return false
    }
  }, [apiBase, fetchAudioConfig, initializePeerConnection, getLocalMediaStream, addLocalAudioTracks])

  /**
   * Reject an incoming call
   */
  const rejectIncomingCall = useCallback(async (sessionId, currentUser) => {
    try {
      console.log(`[VoiceChat] Rejecting call ${sessionId}`)
      
      await axios.post(`${apiBase}/api/voice/reject/${sessionId}`, null, {
        params: { user: currentUser }
      })
      
      // Remove from incoming calls list
      setIncomingCalls(prev => prev.filter(call => call.sessionId !== sessionId))
      console.log('[VoiceChat] Call rejected')
      return true
    } catch (err) {
      console.error('[VoiceChat] Failed to reject call:', err)
      return false
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
        console.log(`[VoiceChat] Initiating voice call from ${currentUser} to ${targetUser}`)

        // 1. Notify backend about voice session
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

        console.log('[VoiceChat] Voice session created on backend:', newSessionId)

        // Store remote peer info
        setRemotePeerInfo({
          username: targetUser,
          ip: targetIp,
          port: targetPort,
        })

        setSessionId(newSessionId)

        // 2. Fetch audio configuration
        const config = await fetchAudioConfig()
        setAudioConfig(config)

        // 3. Initialize WebRTC infrastructure
        const peerConnection = await initializePeerConnection()

        // 4. Get local microphone stream
        const mediaStream = await getLocalMediaStream()

        // 5. Add local audio tracks to peer connection
        await addLocalAudioTracks(peerConnection, mediaStream)

        // 6. CRITICAL: Create and send WebRTC offer via signaling server
        console.log('[VoiceChat] Creating WebRTC offer for proper peer connection...')
        try {
          const offer = await peerConnection.createOffer({
            offerToReceiveAudio: true,
            offerToReceiveVideo: false,
          })
          
          // Set our local description
          await peerConnection.setLocalDescription(offer)
          console.log('[VoiceChat] Local offer SDP created and set')
          
          // Store the offer on the backend so target peer can retrieve it
          await axios.post(`${apiBase}/api/voice/sdp/offer/${newSessionId}`, {
            sdp: offer.sdp
          })
          console.log('[VoiceChat] Offer sent to backend for target peer')
          
          // 7. Poll for the answer from the target peer
          console.log('[VoiceChat] Waiting for answer from target peer...')
          let answerReceived = false
          let pollAttempts = 0
          const maxAttempts = 150 // 30 seconds with 200ms polls
          
          while (!answerReceived && pollAttempts < maxAttempts) {
            await new Promise(r => setTimeout(r, 200)) // Wait 200ms between polls
            
            try {
              const answerResponse = await axios.get(`${apiBase}/api/voice/sdp/answer/${newSessionId}`)
              
              if (answerResponse.status === 200 && answerResponse.data.sdp) {
                console.log('[VoiceChat] Answer received from target peer!')
                
                // Set remote description with the answer
                const answerSdp = new RTCSessionDescription({
                  type: 'answer',
                  sdp: answerResponse.data.sdp
                })
                await peerConnection.setRemoteDescription(answerSdp)
                console.log('[VoiceChat] Remote answer SDP set successfully')
                answerReceived = true
              }
            } catch (pollErr) {
              // 204 means not ready yet, 404 means session not found - both are OK for polling
              if (pollErr.response?.status !== 204 && pollErr.response?.status !== 404) {
                console.warn('[VoiceChat] Error polling for answer:', pollErr.message)
              }
            }
            
            pollAttempts++
          }
          
          if (!answerReceived) {
            console.warn('[VoiceChat] Timeout waiting for answer from target peer (30s)')
            // Continue anyway - might receive late
          }
        } catch (sdpErr) {
          console.error('[VoiceChat] SDP exchange failed:', sdpErr)
          // Continue with basic connection anyway
        }
        
        setStatus('connected')
        setMicrophoneActive(true)
        
        console.log('[VoiceChat] Voice call established')
        console.log('[VoiceChat] Audio streaming ready')
        console.log('[VoiceChat] Session:', newSessionId)
        console.log('[VoiceChat] Local user:', currentUser, '-> Remote user:', targetUser)

        return newSessionId
      } catch (err) {
        const errorMessage = err.response?.data?.message || err.message || 'Voice call initiation failed'
        setError(errorMessage)
        setStatus('error')
        console.error('[VoiceChat] Voice call initiation error:', err)
        return null
      }
    },
    [apiBase, fetchAudioConfig, initializePeerConnection, getLocalMediaStream, addLocalAudioTracks]
  )

  /**
   * Terminates the current voice session
   */
  const endVoiceCall = useCallback(async () => {
    if (!sessionRef.current) {
      return
    }

    try {
      console.log('[VoiceChat] Ending voice call, session:', sessionRef.current)

      // Notify server to terminate session
      await axios.post(`${apiBase}/api/voice/terminate/${sessionRef.current}`)
    } catch (err) {
      console.error('[VoiceChat] Error terminating voice session:', err)
    } finally {
      // Clean up WebRTC
      if (peerConnectionRef.current) {
        peerConnectionRef.current.close()
        peerConnectionRef.current = null
      }

      // Stop audio streams
      if (mediaStreamRef.current) {
        mediaStreamRef.current.getTracks().forEach((track) => {
          track.stop()
        })
        mediaStreamRef.current = null
      }

      // Reset state
      setSessionId(null)
      setStatus('idle')
      setError('')
      setRemotePeerInfo(null)
      setMicrophoneActive(false)
      console.log('[VoiceChat] Voice call ended')
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
      console.error('[VoiceChat] Failed to get session status:', err)
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
      setMicrophoneActive((prev) => !prev)
      console.log('[VoiceChat] Microphone toggled:', !microphoneActive)
    }
  }, [microphoneActive])

  /**
   * Get remote audio reference for attaching to HTML audio element
   */
  const getRemoteAudioRef = useCallback(() => {
    return remoteAudioRef
  }, [])

  /**
   * Set the remote audio element (must be called by VoicePanel after mounting)
   */
  const setRemoteAudioElement = useCallback((audioElement) => {
    console.log('[VoiceChat] 📌 Setting remote audio element:', !!audioElement)
    if (audioElement) {
      console.log('[VoiceChat] Audio element details:', {
        nodeName: audioElement.nodeName,
        autoplay: audioElement.autoplay,
        muted: audioElement.muted,
        volume: audioElement.volume
      })
    }
    remoteAudioRef.current = audioElement
    
    // If we already have a remote stream waiting, connect it now
    if (remoteStreamRef.current && audioElement) {
      console.log('[VoiceChat] ✅ Found stored remote stream! Connecting to newly set audio element...')
      console.log('[VoiceChat] Stored stream details:', {
        id: remoteStreamRef.current.id,
        active: remoteStreamRef.current.active,
        tracks: remoteStreamRef.current.getTracks().length
      })
      
      audioElement.srcObject = remoteStreamRef.current
      console.log('[VoiceChat] srcObject set, attempting play...')
      
      audioElement.play()
        .then(() => {
          console.log('[VoiceChat] ✅✅✅ LATE STREAM CONNECTION SUCCESSFUL! Audio should be playing now! ✅✅✅')
        })
        .catch(err => {
          console.error('[VoiceChat] ❌ Autoplay blocked on late connection:', err.message)
        })
    } else if (!remoteStreamRef.current) {
      console.log('[VoiceChat] No stored stream yet, will connect when ontrack fires')
    }
  }, [])

  return {
    // State
    sessionId,
    status,
    error,
    remotePeerInfo,
    microphoneActive,
    audioConfig,
    incomingCalls,

    // Methods
    initiateVoiceCall,
    endVoiceCall,
    toggleMicrophone,
    getSessionStatus,
    fetchAudioConfig,
    getRemoteAudioRef,
    checkIncomingCalls,
    acceptIncomingCall,
    rejectIncomingCall,
    setRemoteAudioElement,  // ✅ NEW: Call this to set audio element

    // Utils
    isConnected: status === 'connected',
    isConnecting: status === 'connecting',
    hasError: status === 'error',
  }
}
