import { useState, useCallback, useRef, useEffect } from 'react'
import {
  createWhiteboardSession,
  sendWhiteboardDrawCommand,
  fetchWhiteboardCommands,
  closeWhiteboardSession,
  fetchPendingWhiteboardSessions
} from '../api.js'

/**
 * Custom React hook for managing shared whiteboard sessions.
 * Handles drawing commands, synchronization, and real-time updates.
 */
export function useWhiteboard() {
  const [sessionId, setSessionId] = useState(null)
  const [isOpen, setIsOpen] = useState(false)
  const [otherUser, setOtherUser] = useState(null)
  const [commands, setCommands] = useState([])
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState(null)
  const [pendingSessions, setPendingSessions] = useState([])
  const [sessionClosedByOther, setSessionClosedByOther] = useState(false)
  
  const syncIntervalRef = useRef(null)

  /**
   * Create a new whiteboard session with another user
   */
  const createSession = useCallback(async (user1, user2) => {
    if (!user1 || !user2) {
      setError('Missing user information')
      return null
    }

    try {
      const response = await createWhiteboardSession(user1, user2)

      const newSessionId = response.sessionId
      setSessionId(newSessionId)
      setOtherUser(user1) // Store the current user for authorization
      setIsOpen(true)
      setCommands([])
      setError(null)

      console.log('[Whiteboard] Session created:', newSessionId)
      return newSessionId
    } catch (err) {
      console.error('[Whiteboard] Failed to create session:', err)
      setError(err.message || 'Failed to create session')
      return null
    }
  }, [])

  /**
   * Send a drawing command to the server
   */
  const sendDrawCommand = useCallback(async (x1, y1, x2, y2, color = '#000000', thickness = 2) => {
    if (!sessionId) return false

    try {
      await sendWhiteboardDrawCommand(
        sessionId,
        otherUser, // username doesn't matter for drawing, just needs to be valid
        'draw',
        x1,
        y1,
        x2,
        y2,
        color,
        thickness
      )

      // Don't add to local state - let sync handle it for consistency
      // This ensures both users see the same thing at the same time
      return true
    } catch (err) {
      console.error('[Whiteboard] Failed to send draw command:', err)
      return false
    }
  }, [sessionId, otherUser])

  /**
   * Send a clear command to the server
   */
  const sendClearCommand = useCallback(async () => {
    if (!sessionId) return false

    try {
      await sendWhiteboardDrawCommand(
        sessionId,
        otherUser,
        'clear',
        0, 0, 0, 0,
        '#000000',
        2
      )

      setCommands([])
      return true
    } catch (err) {
      console.error('[Whiteboard] Failed to send clear command:', err)
      return false
    }
  }, [sessionId, otherUser])

  /**
   * Sync commands from the server (get updates from other user)
   */
  const syncCommands = useCallback(async () => {
    if (!sessionId || !otherUser || syncing) return

    setSyncing(true)
    try {
      const response = await fetchWhiteboardCommands(sessionId, otherUser)
      const serverCommands = response.commands || []
      
      // Always update with server commands to ensure synchronization
      // The server is the source of truth
      if (serverCommands && Array.isArray(serverCommands)) {
        if (serverCommands.length !== commands.length) {
          console.log('[Whiteboard] Syncing commands:', serverCommands.length, 'from server vs', commands.length, 'local')
          setCommands(serverCommands)
        }
      }
    } catch (err) {
      // If we get a 404 or 403, the session was closed by the other user
      if (err.status === 404 || err.status === 403) {
        console.log('[Whiteboard] Session was closed by other user')
        setSessionClosedByOther(true)
        setSessionId(null)
        setIsOpen(false)
        setOtherUser(null)
        setCommands([])
      } else {
        console.error('[Whiteboard] Failed to sync commands:', err)
      }
    } finally {
      setSyncing(false)
    }
  }, [sessionId, otherUser, commands.length, syncing])

  /**
   * Close the whiteboard session
   */
  const closeSession = useCallback(async () => {
    if (!sessionId) return

    try {
      await closeWhiteboardSession(sessionId, otherUser || 'user')

      console.log('[Whiteboard] Session closed:', sessionId)
    } catch (err) {
      console.error('[Whiteboard] Failed to close session:', err)
    } finally {
      setSessionId(null)
      setIsOpen(false)
      setOtherUser(null)
      setCommands([])
      setError(null)
      setSessionClosedByOther(false)
    }
  }, [sessionId, otherUser])

  /**
   * Acknowledge that we've seen the "closed by other" notification
   */
  const acknowledgeSessionClosed = useCallback(() => {
    setSessionClosedByOther(false)
  }, [])

  /**
   * Check for pending whiteboard invitations
   */
  const checkPendingSessions = useCallback(async (username) => {
    if (!username) return

    try {
      const sessions = await fetchPendingWhiteboardSessions(username)
      setPendingSessions(sessions || [])
    } catch (err) {
      console.error('[Whiteboard] Failed to fetch pending sessions:', err)
    }
  }, [])

  /**
   * Accept an incoming whiteboard invitation
   */
  const acceptInvitation = useCallback(async (session) => {
    setSessionId(session.sessionId)
    setOtherUser(session.otherUser)
    setIsOpen(true)
    setCommands([])
    setPendingSessions([]) // Clear invitations
    console.log('[Whiteboard] Accepted invitation to session:', session.sessionId)
  }, [])

  /**
   * Reject an incoming whiteboard invitation
   */
  const rejectInvitation = useCallback(async (sessionId) => {
    // Just remove from pending list - don't close the session
    setPendingSessions(prev => prev.filter(s => s.sessionId !== sessionId))
    console.log('[Whiteboard] Rejected invitation to session:', sessionId)
  }, [])

  /**
   * Start periodic synchronization (poll for updates)
   */
  useEffect(() => {
    if (isOpen && sessionId) {
      // Sync every 500ms for real-time feel
      syncIntervalRef.current = setInterval(() => {
        syncCommands()
      }, 500)

      return () => {
        if (syncIntervalRef.current) {
          clearInterval(syncIntervalRef.current)
        }
      }
    }
  }, [isOpen, sessionId, syncCommands])

  return {
    sessionId,
    isOpen,
    otherUser,
    commands,
    error,
    pendingSessions,
    sessionClosedByOther,
    createSession,
    sendDrawCommand,
    sendClearCommand,
    closeSession,
    syncCommands,
    checkPendingSessions,
    acceptInvitation,
    rejectInvitation,
    acknowledgeSessionClosed
  }
}
