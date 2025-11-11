/**
 * Incoming Whiteboard Invitation Modal
 * Shows notifications when another user opens a whiteboard session
 */
function IncomingWhiteboardModal({ pendingSessions, currentUser, onAccept, onReject }) {
  if (!pendingSessions || pendingSessions.length === 0) {
    return null
  }

  // Filter out sessions where current user is the initiator (they opened it)
  const incomingSessions = pendingSessions.filter(
    session => session.participant === currentUser
  )

  if (incomingSessions.length === 0) {
    return null
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4">
      <div className="w-full max-w-md space-y-4">
        {incomingSessions.map((session) => (
          <div
            key={session.sessionId}
            className="rounded-2xl border border-white/10 bg-slate-900 p-6 shadow-2xl animate-in fade-in slide-in-from-bottom-4"
          >
            <div className="mb-4 flex items-center gap-4">
              <div className="flex h-14 w-14 items-center justify-center rounded-full bg-brand-500 text-2xl">
                🎨
              </div>
              <div className="flex-1">
                <h3 className="text-lg font-bold text-white">
                  Whiteboard Invitation
                </h3>
                <p className="text-sm text-slate-400">
                  From <span className="font-semibold text-brand-400">{session.otherUser}</span>
                </p>
              </div>
            </div>

            <p className="mb-6 text-sm text-slate-300">
              {session.otherUser} has opened a whiteboard and is inviting you to collaborate.
            </p>

            <div className="flex gap-3">
              <button
                onClick={() => onAccept(session)}
                className="flex-1 rounded-xl bg-linear-to-r from-green-600 to-green-500 px-4 py-3 text-sm font-bold text-white shadow-lg transition hover:scale-105 hover:from-green-500 hover:to-green-400"
              >
                ✓ Join Whiteboard
              </button>
              <button
                onClick={() => onReject(session.sessionId)}
                className="flex-1 rounded-xl border border-red-500/50 bg-red-600/10 px-4 py-3 text-sm font-bold text-red-400 transition hover:bg-red-600/20"
              >
                ✕ Decline
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

export default IncomingWhiteboardModal
