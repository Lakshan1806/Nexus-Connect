import { useEffect, useRef, useState } from "react";
import { useVoiceChat } from "../hooks/useVoiceChat.js";

/**
 * VoicePanel UI Component for P2P WebRTC voice chat.
 *
 * Displays:
 * - Call initiation/termination button
 * - Connection status indicator with real-time status
 * - Call duration timer
 * - Microphone status and toggle
 * - Error messages
 * - Remote audio element (hidden, used for playback)
 *
 * Member 5 (P2P Real-time Voice Streaming - WebRTC)
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
  const voiceChat = useVoiceChat(apiBase);
  const [callDuration, setCallDuration] = useState(0);
  const callTimerRef = useRef(null);
  const remoteAudioRef = useRef(null);

  // Prevent calling yourself
  const canCall =
    currentUser &&
    selectedUser &&
    selectedUser.user !== currentUser.user &&
    peerDetails &&
    peerDetails.voiceUdp > 0;

  // Timer for call duration
  useEffect(() => {
    if (voiceChat.isConnected) {
      callTimerRef.current = setInterval(() => {
        setCallDuration((prev) => prev + 1);
      }, 1000);
    }
    return () => {
      if (callTimerRef.current) {
        clearInterval(callTimerRef.current);
      }
    };
  }, [voiceChat.isConnected]);

  // Attach remote audio ref to hook's remote audio reference
  useEffect(() => {
    const remoteAudioRefFromHook = voiceChat.getRemoteAudioRef();
    if (remoteAudioRefFromHook && remoteAudioRef.current) {
      remoteAudioRefFromHook.current = remoteAudioRef.current;
    }
  }, [voiceChat]);

  const handleStartCall = async () => {
    if (!canCall || !localVoicePort) {
      return;
    }

    const sessionId = await voiceChat.initiateVoiceCall(
      currentUser.user,
      selectedUser.user,
      localVoicePort,
      peerDetails,
    );

    if (sessionId) {
      setCallDuration(0);
      onVoiceSessionStart?.(sessionId);
    }
  };

  const handleEndCall = async () => {
    await voiceChat.endVoiceCall();
    setCallDuration(0);
    onVoiceSessionEnd?.();
  };

  const formatDuration = (seconds) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, "0")}`;
  };

  const getStatusColor = () => {
    switch (voiceChat.status) {
      case "connected":
        return "text-green-400";
      case "connecting":
        return "text-yellow-400";
      case "error":
        return "text-red-400";
      default:
        return "text-slate-400";
    }
  };

  const getStatusText = () => {
    switch (voiceChat.status) {
      case "idle":
        return "Ready";
      case "connecting":
        return "Establishing connection...";
      case "connected":
        return `Connected - ${formatDuration(callDuration)}`;
      case "error":
        return `Error: ${voiceChat.error || "Unknown"}`;
      default:
        return "Unknown";
    }
  };

  if (!selectedUser) {
    return null;
  }

  return (
    <>
      {/* Remote Audio Element (hidden, for playback) */}
      <audio
        ref={remoteAudioRef}
        autoPlay
        playsInline
        style={{ display: "none" }}
      />

      <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-4 text-sm text-slate-300">
        <h3 className="text-xs font-semibold tracking-[0.2em] text-slate-400 uppercase">
          🎙️ Voice Chat
        </h3>

        <div className="mt-4 space-y-3">
          {/* Status Indicator - Large and Clear */}
          <div className="flex items-center gap-3 rounded-lg bg-slate-800/30 p-3">
            <div
              className={`h-3 w-3 rounded-full transition-all ${
                voiceChat.isConnected
                  ? "animate-pulse bg-green-400 shadow-lg shadow-green-400/50"
                  : voiceChat.isConnecting
                    ? "animate-pulse bg-yellow-400"
                    : voiceChat.hasError
                      ? "bg-red-400"
                      : "bg-slate-600"
              }`}
            />
            <div className="flex-1">
              <p className={`text-sm font-semibold ${getStatusColor()}`}>
                {getStatusText()}
              </p>
              <p className="mt-0.5 text-xs text-slate-400">
                📞 {selectedUser.user}
              </p>
            </div>
          </div>

          {/* Peer Information - When Connected */}
          {voiceChat.isConnected && voiceChat.remotePeerInfo && (
            <div className="rounded border border-green-900/30 bg-green-950/30 p-3 text-xs">
              <p className="mb-1 font-medium text-green-300">Connected to:</p>
              <p className="font-mono text-green-200">
                {voiceChat.remotePeerInfo.ip}:{voiceChat.remotePeerInfo.port}
              </p>
              {voiceChat.audioConfig && (
                <p className="mt-2 text-xs text-green-300/70">
                  🔊 {voiceChat.audioConfig.sampleRate} Hz,{" "}
                  {voiceChat.audioConfig.channels} ch,{" "}
                  {voiceChat.audioConfig.bitsPerSample}-bit
                </p>
              )}
            </div>
          )}

          {/* Error Message */}
          {voiceChat.hasError && (
            <div className="rounded border border-red-900/50 bg-red-950/50 p-3">
              <p className="text-xs font-medium text-red-300">
                ⚠️ {voiceChat.error}
              </p>
            </div>
          )}

          {/* Microphone Controls - When Connected */}
          {voiceChat.isConnected && (
            <div className="flex items-center gap-2 rounded bg-slate-800/30 p-2">
              <button
                onClick={voiceChat.toggleMicrophone}
                className={`flex flex-1 items-center justify-center gap-2 rounded px-3 py-2 text-xs font-semibold transition-all ${
                  voiceChat.microphoneActive
                    ? "bg-red-600 text-white hover:bg-red-500"
                    : "bg-slate-700 text-slate-300 hover:bg-slate-600"
                }`}
              >
                {voiceChat.microphoneActive ? "🎤" : "🔇"}
                {voiceChat.microphoneActive ? "Mic On" : "Mic Muted"}
              </button>
            </div>
          )}

          {/* Call Control Buttons */}
          <div className="mt-4 flex gap-2">
            {!voiceChat.isConnected ? (
              <button
                onClick={handleStartCall}
                disabled={!canCall}
                className={`flex flex-1 items-center justify-center gap-2 rounded px-4 py-3 text-sm font-bold transition-all ${
                  canCall
                    ? "transform cursor-pointer bg-gradient-to-r from-green-600 to-green-500 text-white shadow-lg hover:scale-105 hover:from-green-500 hover:to-green-400 hover:shadow-xl"
                    : "cursor-not-allowed bg-slate-700 text-slate-400 opacity-50"
                }`}
              >
                <span>📞</span> Start Call
              </button>
            ) : (
              <button
                onClick={handleEndCall}
                className="flex flex-1 transform cursor-pointer items-center justify-center gap-2 rounded bg-gradient-to-r from-red-600 to-red-500 px-4 py-3 text-sm font-bold text-white shadow-lg transition-all hover:scale-105 hover:from-red-500 hover:to-red-400 hover:shadow-xl"
              >
                <span>❌</span> End Call
              </button>
            )}
          </div>

          {/* Help Text */}
          {!voiceChat.isConnected && !canCall && (
            <div className="rounded border border-amber-900/30 bg-amber-950/30 p-3 text-xs text-amber-200">
              <p className="mb-1 font-medium">Cannot start call:</p>
              <ul className="ml-3 list-disc space-y-1 text-amber-300/80">
                {!selectedUser && <li>Select a user first</li>}
                {selectedUser?.user === currentUser.user && (
                  <li>Cannot call yourself</li>
                )}
                {!peerDetails && <li>Peer details not available</li>}
                {peerDetails?.voiceUdp <= 0 && (
                  <li>Peer has not enabled voice</li>
                )}
              </ul>
            </div>
          )}
        </div>
      </div>
    </>
  );
}

export default VoicePanel;
