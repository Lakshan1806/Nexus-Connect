import { useState } from "react";

/**
 * Modal component for displaying and handling incoming voice calls.
 * Shows caller name and provides Accept/Reject buttons.
 *
 * Member 5 (P2P Real-time Voice Streaming - UDP)
 */
function IncomingCallModal({
  incomingCalls,
  currentUser,
  localVoicePort,
  onAccept,
  onReject,
}) {
  const [processingCallId, setProcessingCallId] = useState(null);

  if (!incomingCalls || incomingCalls.length === 0) {
    return null;
  }

  const call = incomingCalls[0]; // Show first incoming call

  console.log("[IncomingCallModal] Rendering with call:", call);

  const handleAccept = async () => {
    setProcessingCallId(call.sessionId);
    try {
      const result = await onAccept(
        call.sessionId,
        currentUser,
        localVoicePort,
      );
      if (result) {
        console.log("[IncomingCallModal] Call accepted successfully");
      }
    } finally {
      setProcessingCallId(null);
    }
  };

  const handleReject = async () => {
    setProcessingCallId(call.sessionId);
    try {
      const result = await onReject(call.sessionId, currentUser);
      if (result) {
        console.log("[IncomingCallModal] Call rejected successfully");
      }
    } finally {
      setProcessingCallId(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="w-full max-w-md rounded-xl border border-blue-500/30 bg-gradient-to-br from-slate-900 to-slate-800 p-8 shadow-2xl">
        {/* Header */}
        <div className="mb-6 text-center">
          <h2 className="mb-2 text-3xl font-bold text-blue-400">
            📞 Incoming Call
          </h2>
          <div className="mx-auto mb-4 h-12 w-12 animate-pulse rounded-full bg-green-500"></div>
        </div>

        {/* Caller Info */}
        <div className="mb-6 rounded-lg border border-slate-600 bg-slate-700/50 p-4 text-center">
          <p className="mb-1 text-sm text-gray-300">From:</p>
          <p className="text-2xl font-bold text-white">{call.caller}</p>
          <p className="mt-2 text-sm text-gray-400">
            {call.callerIp}:{call.callerPort}
          </p>
        </div>

        {/* Ringing Animation */}
        <div className="mb-6 flex justify-center gap-2">
          <div
            className="h-2 w-2 animate-bounce rounded-full bg-blue-400"
            style={{ animationDelay: "0s" }}
          ></div>
          <div
            className="h-2 w-2 animate-bounce rounded-full bg-blue-400"
            style={{ animationDelay: "0.2s" }}
          ></div>
          <div
            className="h-2 w-2 animate-bounce rounded-full bg-blue-400"
            style={{ animationDelay: "0.4s" }}
          ></div>
        </div>

        {/* Action Buttons */}
        <div className="flex gap-3">
          <button
            onClick={handleAccept}
            disabled={processingCallId !== null}
            className="flex flex-1 transform items-center justify-center gap-2 rounded-lg bg-gradient-to-r from-green-600 to-green-500 px-4 py-3 font-bold text-white transition-all duration-200 hover:scale-105 hover:from-green-500 hover:to-green-400 active:scale-95 disabled:cursor-not-allowed disabled:from-gray-600 disabled:to-gray-500"
          >
            {processingCallId === call.sessionId ? (
              <>
                <span className="inline-block animate-spin">⏳</span>
                Accepting...
              </>
            ) : (
              <>✅ Accept</>
            )}
          </button>
          <button
            onClick={handleReject}
            disabled={processingCallId !== null}
            className="flex flex-1 transform items-center justify-center gap-2 rounded-lg bg-gradient-to-r from-red-600 to-red-500 px-4 py-3 font-bold text-white transition-all duration-200 hover:scale-105 hover:from-red-500 hover:to-red-400 active:scale-95 disabled:cursor-not-allowed disabled:from-gray-600 disabled:to-gray-500"
          >
            {processingCallId === call.sessionId ? (
              <>
                <span className="inline-block animate-spin">⏳</span>
                Rejecting...
              </>
            ) : (
              <>❌ Reject</>
            )}
          </button>
        </div>

        {/* Additional Info */}
        <p className="mt-4 text-center text-xs text-gray-400">
          Session ID: {call.sessionId}
        </p>
      </div>
    </div>
  );
}

export default IncomingCallModal;
