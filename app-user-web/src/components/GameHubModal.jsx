import { useEffect, useState } from "react"
import { createPortal } from "react-dom"

function GameHubModal({ open, onClose, currentUser, selectedUser, ticTacToe }) {
  if (typeof document === "undefined" || !open) return null

  const state = ticTacToe?.state
  const lastOutcome = ticTacToe?.lastOutcome
  const board = state?.board ?? ["---", "---", "---"]
  const loading = ticTacToe?.loading
  const resolvedStatus = state?.status ?? lastOutcome?.status ?? null
  const resolvedWinner = state?.winner ?? lastOutcome?.winner ?? null
  const mySymbol = state
    ? state.playerX === currentUser
      ? "X"
      : state.playerO === currentUser
      ? "O"
      : null
    : lastOutcome
    ? lastOutcome.playerX === currentUser
      ? "X"
      : lastOutcome.playerO === currentUser
      ? "O"
      : null
    : null
  const opponentFromState = (() => {
    if (state && mySymbol) {
      return state.playerX === currentUser ? state.playerO : state.playerX
    }
    if (lastOutcome && mySymbol) {
      return lastOutcome.playerX === currentUser ? lastOutcome.playerO : lastOutcome.playerX
    }
    return null
  })()
  const selectedPeer = selectedUser && selectedUser.user !== currentUser ? selectedUser.user : null
  const challengeTarget = selectedPeer ?? opponentFromState
  const isMyTurn = state?.status === "IN_PROGRESS" && state.currentTurn === currentUser
  const canMove = (row, col) => {
    const symbol = board[row]?.[col]
    return isMyTurn && !loading && (symbol === "-" || symbol === undefined)
  }

  const statusLabel = (() => {
    if (!resolvedStatus) return "No active match."
    switch (resolvedStatus) {
      case "IN_PROGRESS":
        return isMyTurn ? "Your turn" : "Waiting for opponent"
      case "WON_X":
      case "WON_O":
        return resolvedWinner === currentUser ? "You win!" : `${resolvedWinner} wins`
      case "DRAW":
        return "Match ended in a draw"
      case "RESIGNED":
        return resolvedWinner === currentUser ? "Opponent resigned" : "You resigned"
      default:
        return resolvedStatus
    }
  })()

  const handleStart = () => {
    if (challengeTarget) {
      ticTacToe?.startGame(challengeTarget)
    }
  }

  const handleMove = (row, col) => {
    if (state?.gameId && canMove(row, col)) {
      ticTacToe?.makeMove(row, col)
    }
  }

  const handleResign = () => {
    if (state?.gameId) {
      ticTacToe?.resignGame()
    }
  }

  const [showCelebration, setShowCelebration] = useState(false)
  useEffect(() => {
    if (lastOutcome && lastOutcome.status && lastOutcome.status !== "IN_PROGRESS") {
      setShowCelebration(true)
      const timer = setTimeout(() => setShowCelebration(false), 2500)
      return () => clearTimeout(timer)
    }
  }, [lastOutcome])

  const outcomeText = (() => {
    if (!lastOutcome) return null
    if (lastOutcome.status === "DRAW") return "It's a draw!"
    if (lastOutcome.status === "RESIGNED") {
      return lastOutcome.winner === currentUser ? "Opponent resigned!" : "You resigned!"
    }
    if (lastOutcome.status === "WON_X" || lastOutcome.status === "WON_O") {
      return lastOutcome.winner === currentUser ? "Victory!" : `${lastOutcome.winner} wins!`
    }
    return null
  })()

  const content = (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 px-4 py-8 backdrop-blur-md">
      <div className="relative w-full max-w-4xl overflow-hidden rounded-3xl border border-white/10 bg-gradient-to-br from-slate-900 via-slate-900/90 to-slate-950 shadow-2xl">
        {showCelebration && outcomeText && (
          <div className="pointer-events-none absolute inset-0 z-10 flex flex-col items-center justify-center bg-slate-950/80 text-center">
            <p className="text-4xl font-semibold text-white animate-pulse">{outcomeText}</p>
            <p className="mt-2 text-sm text-slate-300">Board resets automatically for the next round.</p>
          </div>
        )}
        <button
          type="button"
          onClick={onClose}
          className="absolute right-4 top-4 rounded-full border border-white/20 bg-white/5 px-3 py-1 text-xs font-semibold text-white transition hover:bg-white/15"
        >
          Close
        </button>

        <div className="grid gap-8 p-8 md:grid-cols-[1.1fr_0.9fr]">
          <div className="space-y-6">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.4em] text-slate-400">Game Hub</p>
              <h2 className="text-3xl font-semibold text-white">Tic-Tac-Toe Arena</h2>
              <p className="text-sm text-slate-400">{statusLabel}</p>
            </div>

            <div className="flex items-center justify-between rounded-2xl border border-white/10 bg-slate-950/40 p-4 text-sm text-white">
              <PlayerBadge label="You" user={currentUser} symbol={mySymbol ?? "-"} active={isMyTurn} />
              <div className="text-xs uppercase tracking-[0.5em] text-slate-500">vs</div>
              <PlayerBadge
                label="Opponent"
                user={opponentFromState ?? challengeTarget ?? "Select a peer"}
                symbol={
                  state && mySymbol
                    ? mySymbol === "X"
                      ? "O"
                      : "X"
                    : selectedPeer
                    ? "?"
                    : "-"
                }
                active={!isMyTurn && state?.status === "IN_PROGRESS"}
              />
            </div>

            <div className="grid grid-cols-3 gap-3 rounded-3xl border border-white/5 bg-slate-900/60 p-4 shadow-inner">
              {[0, 1, 2].map((row) =>
                [0, 1, 2].map((col) => {
                  const symbol = board[row]?.[col]
                  const highlight =
                    state?.lastMoveRow === row && state?.lastMoveCol === col ? "ring-2 ring-brand-400/70" : ""
                  return (
                    <button
                      key={`${row}-${col}`}
                      type="button"
                      disabled={!canMove(row, col)}
                      onClick={() => handleMove(row, col)}
                      className={`aspect-square rounded-2xl border border-white/10 bg-slate-900/80 text-3xl font-semibold text-white transition hover:border-brand-400 hover:text-brand-200 disabled:cursor-not-allowed disabled:opacity-60 ${highlight}`}
                    >
                      {symbol && symbol !== "-" ? symbol : ""}
                    </button>
                  )
                }),
              )}
            </div>
          </div>

          <div className="flex flex-col gap-6 rounded-3xl border border-white/10 bg-slate-950/50 p-6">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.4em] text-slate-500">Actions</p>
              <div className="mt-4 space-y-3">
                <button
                  type="button"
                  onClick={handleStart}
                  disabled={!challengeTarget || loading}
                  className="w-full rounded-2xl bg-emerald-500/90 px-4 py-3 text-sm font-semibold text-white transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:bg-emerald-500/60"
                >
                  {state?.status === "IN_PROGRESS" ? "Restart Match" : "Send Challenge"}
                </button>
                <button
                  type="button"
                  onClick={ticTacToe?.refresh}
                  className="w-full rounded-2xl border border-white/20 px-4 py-3 text-sm font-semibold text-slate-200 transition hover:border-brand-400/60 hover:text-white"
                >
                  Refresh State
                </button>
                <button
                  type="button"
                  onClick={handleResign}
                  disabled={!state || state.status !== "IN_PROGRESS" || loading}
                  className="w-full rounded-2xl border border-red-400/50 px-4 py-3 text-sm font-semibold text-red-200 transition hover:bg-red-500/10 disabled:cursor-not-allowed disabled:border-white/10 disabled:text-slate-500"
                >
                  Resign Match
                </button>
              </div>
            </div>

            <div className="rounded-2xl border border-white/10 bg-slate-900/80 p-4 text-sm text-slate-200">
              <p className="text-xs font-semibold uppercase tracking-[0.4em] text-slate-500">How it works</p>
              <ul className="mt-3 list-disc space-y-2 pl-5 text-xs leading-relaxed text-slate-400">
                <li>Games run on the multiplexed NIO server for low latency.</li>
                <li>Your move is validated server-side then streamed to both players.</li>
                <li>Select a peer in the lobby, open Game Hub, and tap “Send Challenge”.</li>
                <li>When a match ends, a celebration overlay shows the result and the board resets automatically.</li>
              </ul>
            </div>

            {ticTacToe?.error && (
              <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-xs text-amber-100">
                {ticTacToe.error}
              </div>
            )}

            {loading && <p className="text-center text-xs text-slate-500">Working...</p>}
          </div>
        </div>
      </div>
    </div>
  )

  return createPortal(content, document.body)
}

function PlayerBadge({ label, user, symbol, active }) {
  return (
    <div className="space-y-1">
      <p className="text-xs uppercase tracking-[0.3em] text-slate-500">{label}</p>
      <div className="flex items-center gap-3 rounded-2xl border border-white/10 bg-slate-900/60 px-4 py-3">
        <div
          className={`flex h-10 w-10 items-center justify-center rounded-2xl text-xl font-semibold ${
            symbol === "X"
              ? "bg-brand-500/30 text-brand-200"
              : symbol === "O"
              ? "bg-fuchsia-500/20 text-fuchsia-200"
              : "bg-white/10 text-slate-200"
          }`}
        >
          {symbol ?? "-"}
        </div>
        <div>
          <p className="text-sm font-semibold text-white">{user ?? "—"}</p>
          <p className={`text-xs ${active ? "text-emerald-300" : "text-slate-500"}`}>
            {active ? "Active turn" : "Standby"}
          </p>
        </div>
      </div>
    </div>
  )
}

export default GameHubModal
