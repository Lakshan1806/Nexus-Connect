import { useCallback, useEffect, useState } from "react";
import {
  fetchTicTacToeState,
  makeTicTacToeMove,
  resignTicTacToe,
  startTicTacToe,
} from "../api.js";

export function useTicTacToe(activeUser, pollInterval = 5000, resetDelayMs = 3500) {
  const [state, setState] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [lastOutcome, setLastOutcome] = useState(null);

  const refresh = useCallback(async () => {
    if (!activeUser) {
      setState(null);
      setError("");
      return;
    }
    try {
      const snapshot = await fetchTicTacToeState();
      setState(snapshot ?? null);
      if (snapshot) {
        setError("");
      }
    } catch (err) {
      setError(err.message ?? "Unable to load game state");
    }
  }, [activeUser]);

  useEffect(() => {
    let timer;
    if (activeUser) {
      refresh();
      if (pollInterval) {
        timer = setInterval(refresh, pollInterval);
      }
    }
    return () => {
      if (timer) clearInterval(timer);
    };
  }, [activeUser, pollInterval, refresh]);

  useEffect(() => {
    if (!state) return;
    if (state.status && state.status !== "IN_PROGRESS") {
      setLastOutcome({
        ...state,
        finishedAt: Date.now(),
      });
      const timer = setTimeout(() => {
        setState(null);
      }, resetDelayMs);
      return () => clearTimeout(timer);
    }
  }, [state, resetDelayMs]);

  const startGame = useCallback(
    async (opponent) => {
      if (!opponent) return;
      setLoading(true);
      try {
        const snapshot = await startTicTacToe(opponent);
        setLastOutcome(null);
        setState(snapshot);
        setError("");
      } catch (err) {
        setError(err.message ?? "Unable to start game");
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const makeMove = useCallback(
    async (row, col) => {
      if (!state?.gameId) return;
      setLoading(true);
      try {
        const snapshot = await makeTicTacToeMove(state.gameId, row, col);
        setState(snapshot);
        setError("");
      } catch (err) {
        setError(err.message ?? "Move rejected");
      } finally {
        setLoading(false);
      }
    },
    [state],
  );

  const resignGame = useCallback(async () => {
    if (!state?.gameId) return;
    setLoading(true);
    try {
      const snapshot = await resignTicTacToe(state.gameId);
      setState(snapshot);
      setError("");
    } catch (err) {
      setError(err.message ?? "Unable to resign");
    } finally {
      setLoading(false);
    }
  }, [state]);

  return {
    state,
    loading,
    error,
    startGame,
    makeMove,
    resignGame,
    refresh,
    lastOutcome,
  };
}
