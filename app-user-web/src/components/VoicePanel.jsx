import { useEffect, useRef, useState } from 'react'
import { useVoiceChat } from '../hooks/useVoiceChat.js'

/**
 * VoicePanel UI Component for P2P voice chat.
 * 
 * Displays:
 * - Push-to-talk button
 * - Connection status indicator
 * - Call duration timer
 * - Microphone status
 * - Error messages
 * 
 * Member 5 (P2P Real-time Voice Streaming - UDP) implementation.
 */
function VoicePanel({
  apiBase,
  currentUser,
  selectedUser,
  peerDetails,
  localVoicePort,
  onVoiceSessionStart,
  onVoiceSessionEnd,
}) {
  const voiceChat = useVoiceChat(apiBase)
  const [callDuration, setCallDuration] = useState(0)
  const callTimerRef = useRef(null)

  // Prevent calling yourself
  const canCall =
    currentUser &&
    selectedUser &&
    selectedUser.user !== currentUser.user &&
    peerDetails &&
    peerDetails.voiceUdp > 0

  // Timer for call duration
  useEffect(() => {
    if (voiceChat.isConnected) {
      callTimerRef.current = setInterval(() => {
        setCallDuration((prev) => prev + 1)
      }, 1000)
    }
    return () => {
      if (callTimerRef.current) {
        clearInterval(callTimerRef.current)
      }
    }
  }, [voiceChat.isConnected])

  const handleStartCall = async () => {
    if (!canCall || !localVoicePort) {
      return
    }

    const sessionId = await voiceChat.initiateVoiceCall(
      currentUser.user,
      selectedUser.user,
      localVoicePort,
      peerDetails
    )

    if (sessionId) {
      setCallDuration(0)
      onVoiceSessionStart?.(sessionId)
    }
  }

  const handleEndCall = async () => {
    await voiceChat.endVoiceCall()
    setCallDuration(0)
    onVoiceSessionEnd?.()
  }

  const formatDuration = (seconds) => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs.toString().padStart(2, '0')}`
  }

  const getStatusColor = () => {
    switch (voiceChat.status) {
      case 'connected':
        return 'text-green-400'
      case 'connecting':
        return 'text-yellow-400'
      case 'error':
        return 'text-red-400'
      default:
        return 'text-slate-400'
    }
  }

  const getStatusText = () => {
    switch (voiceChat.status) {
      case 'idle':
        return 'Ready for voice call'
      case 'connecting':
        return 'Connecting...'
      case 'connected':
        return `Connected - ${formatDuration(callDuration)}`
      case 'error':
        return `Error: ${voiceChat.error || 'Unknown error'}`
      default:
        return 'Unknown'
    }
  }

  if (!selectedUser) {
    return null
  }

  return (
    <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-4 text-sm text-slate-300">
      <h3 className="text-xs font-semibold tracking-[0.2em] text-slate-400 uppercase">
        Voice Chat
      </h3>

      <div className="mt-3 space-y-3">
        {/* Status Indicator */}
        <div className="flex items-center gap-2">
          <div
            className={`h-2 w-2 rounded-full transition-all ${
              voiceChat.isConnected
                ? 'animate-pulse bg-green-400'
                : 'bg-slate-600'
            }`}
          />
          <p className={`text-xs font-medium ${getStatusColor()}`}>
            {getStatusText()}
          </p>
        </div>

        {/* Peer Information */}
        {voiceChat.isConnected && voiceChat.remotePeerInfo && (
          <div className="rounded bg-slate-800/50 p-2">
            <p className="text-xs text-slate-400">Connected to:</p>
            <p className="font-mono text-xs text-slate-200">
              {voiceChat.remotePeerInfo.ip}:{voiceChat.remotePeerInfo.port}
            </p>
          </div>
        )}

        {/* Audio Config (Debug) */}
        {voiceChat.audioConfig && voiceChat.isConnected && (
          <div className="text-xs text-slate-500">
            <p>
              {voiceChat.audioConfig.sampleRate} Hz, {voiceChat.audioConfig.channels} ch,{' '}
              {voiceChat.audioConfig.bitsPerSample} bit
            </p>
          </div>
        )}

        {/* Error Message */}
        {voiceChat.hasError && (
          <div className="rounded bg-red-950/50 p-2">
            <p className="text-xs text-red-300">{voiceChat.error}</p>
          </div>
        )}

        {/* Microphone Status */}
        {voiceChat.isConnected && (
          <div className="flex items-center gap-2">
            <button
              onClick={voiceChat.toggleMicrophone}
              className={`rounded px-2 py-1 text-xs font-medium transition-all ${
                voiceChat.microphoneActive
                  ? 'bg-red-600 hover:bg-red-500 text-white'
                  : 'bg-slate-700 hover:bg-slate-600 text-slate-300'
              }`}
            >
              {voiceChat.microphoneActive ? '🎤 Muted' : '🔇 Mic Off'}
            </button>
          </div>
        )}

        {/* Call Control Buttons */}
        <div className="flex gap-2">
          {!voiceChat.isConnected ? (
            <button
              onClick={handleStartCall}
              disabled={!canCall}
              className={`flex-1 rounded py-2 px-3 text-xs font-semibold transition-all ${
                canCall
                  ? 'bg-green-600 hover:bg-green-500 text-white cursor-pointer'
                  : 'bg-slate-700 text-slate-400 cursor-not-allowed opacity-50'
              }`}
            >
              📞 Start Call
            </button>
          ) : (
            <button
              onClick={handleEndCall}
              className="flex-1 rounded bg-red-600 py-2 px-3 text-xs font-semibold text-white hover:bg-red-500 transition-all cursor-pointer"
            >
              ❌ End Call
            </button>
          )}
        </div>

        {/* Info Text */}
        {!voiceChat.isConnected && !canCall && (
          <p className="text-xs text-amber-300">
            {!selectedUser
              ? 'Select a user to start voice call'
              : selectedUser.user === currentUser.user
                ? 'Cannot call yourself'
                : !peerDetails
                  ? 'Peer details not available'
                  : peerDetails.voiceUdp <= 0
                    ? 'Peer has not advertised voice UDP port'
                    : 'Check your network connection'}
          </p>
        )}
      </div>
    </div>
  )
}

export default VoicePanel
