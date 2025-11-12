import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import {
  API_BASE,
  connectToLobby,
  disconnectFromLobby as disconnectFromLobbyApi,
  fetchPeerDetails,
  fetchSnapshot,
  fetchCurrentUser,
  sendChat,
  setAuthToken,
  signIn,
  signUp,
} from "./api.js"
import AuthScreen from "./components/AuthScreen.jsx"
import LoginScreen from "./components/LoginScreen.jsx"
import ChatShell from "./components/ChatShell.jsx"
import IncomingWhiteboardModal from "./components/IncomingWhiteboardModal.jsx"
import { useVoiceChat } from "./hooks/useVoiceChat.js"
import { useWhiteboard } from "./hooks/useWhiteboard.js"

const POLL_INTERVAL_MS = 4000
const TOKEN_STORAGE_KEY = "nexusconnect_token"

const dedupeMessages = (messages) => {
  const map = new Map()
  for (const message of messages ?? []) {
    if (!message) continue
    const key = `${message.timestampSeconds ?? 0}-${message.from ?? ""}-${message.text ?? ""}`
    map.set(key, message)
  }
  return Array.from(map.values()).sort((a, b) => (a?.timestampSeconds ?? 0) - (b?.timestampSeconds ?? 0))
}

function App() {
  const [authUser, setAuthUser] = useState(() => {
    if (typeof window === "undefined") return null
    return null
  })
  const [token, setToken] = useState(() => {
    if (typeof window === "undefined") return ""
    return window.localStorage.getItem(TOKEN_STORAGE_KEY) ?? ""
  })
  const [authInitializing, setAuthInitializing] = useState(() => {
    if (typeof window === "undefined") return false
    return !!window.localStorage.getItem(TOKEN_STORAGE_KEY)
  })
  const [authPending, setAuthPending] = useState(false)
  const [authError, setAuthError] = useState("")

  const [connectOptions, setConnectOptions] = useState({
    fileTcp: "",
    voiceUdp: "",
    ipOverride: "",
  })
  const [loginPending, setLoginPending] = useState(false)
  const [loginError, setLoginError] = useState("")

  const [session, setSession] = useState(null)
  const [users, setUsers] = useState([])
  const [messages, setMessages] = useState([])
  const [selectedUser, setSelectedUser] = useState(null)
  const [peerDetails, setPeerDetails] = useState(null)
  const [peerError, setPeerError] = useState("")
  const [peerLoading, setPeerLoading] = useState(false)
  const [messageText, setMessageText] = useState("")
  const [sendingMessage, setSendingMessage] = useState(false)
  const [syncError, setSyncError] = useState("")
  const [lastRefreshed, setLastRefreshed] = useState(null)

  const voiceChat = useVoiceChat(API_BASE)
  const whiteboard = useWhiteboard()

  const sessionRef = useRef(session)
  useEffect(() => {
    sessionRef.current = session
  }, [session])

  const messagesEndRef = useRef(null)
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" })
  }, [messages])

  const timeFormatter = useMemo(
    () =>
      new Intl.DateTimeFormat(undefined, {
        hour: "2-digit",
        minute: "2-digit",
      }),
    [],
  )

  const sortedUsers = useMemo(() => [...users].sort((a, b) => a.user.localeCompare(b.user)), [users])

  const clearAuthState = useCallback(() => {
    setAuthUser(null)
    setToken("")
    setAuthToken(null)
    if (typeof window !== "undefined") {
      window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  }, [])

  useEffect(() => {
    if (!token) {
      setAuthToken(null)
      setAuthInitializing(false)
      return
    }
    setAuthToken(token)
    if (typeof window !== "undefined") {
      window.localStorage.setItem(TOKEN_STORAGE_KEY, token)
    }
    if (authUser) {
      setAuthInitializing(false)
      return
    }
    setAuthInitializing(true)
    fetchCurrentUser()
      .then((user) => {
        setAuthUser(user)
      })
      .catch(() => {
        clearAuthState()
      })
      .finally(() => setAuthInitializing(false))
  }, [token, authUser, clearAuthState])

  useEffect(() => {
    if (!session) {
      setSelectedUser(null)
      return
    }
    setSelectedUser((previous) => {
      if (previous) {
        const stillOnline = sortedUsers.find((user) => user.user === previous.user)
        if (stillOnline) return stillOnline
      }
      const self = sortedUsers.find((user) => user.user === session.user)
      if (self) return self
      return sortedUsers[0] ?? null
    })
  }, [session, sortedUsers])

  const refreshOnce = useCallback(async () => {
    const currentSession = sessionRef.current
    if (!currentSession) return
    const currentUser = currentSession.user
    try {
      const { users: freshUsers, messages: freshMessages } = await fetchSnapshot()
      if (!sessionRef.current || sessionRef.current.user !== currentUser) return
      setUsers(freshUsers)
      setMessages(dedupeMessages(freshMessages))
      setSyncError("")
      setLastRefreshed(Date.now())
    } catch (error) {
      if (sessionRef.current && sessionRef.current.user === currentUser) {
        setSyncError("Connection unstable. Trying to resync...")
      }
      throw error
    }
  }, [])

  useEffect(() => {
    if (!session) return undefined
    let cancelled = false

    const tick = async () => {
      try {
        await refreshOnce()
      } catch (error) {
        if (cancelled) return
        console.debug("refresh failed", error)
      }
    }

    tick()
    const interval = window.setInterval(tick, POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      window.clearInterval(interval)
    }
  }, [session, refreshOnce])

  useEffect(() => {
    if (!session || !selectedUser || selectedUser.user === session.user) {
      setPeerDetails(null)
      setPeerError("")
      setPeerLoading(false)
      return
    }
    let active = true
    setPeerLoading(true)
    setPeerError("")
    fetchPeerDetails(selectedUser.user)
      .then((details) => {
        if (!active) return
        setPeerDetails(details)
        if (!details) {
          setPeerError("Peer is offline or did not publish details yet.")
        }
      })
      .catch((error) => {
        if (!active) return
        setPeerDetails(null)
        setPeerError(error.message || "Unable to load peer details")
      })
      .finally(() => {
        if (active) {
          setPeerLoading(false)
        }
      })
    return () => {
      active = false
    }
  }, [session, selectedUser])

  useEffect(() => {
    if (!session) return

    const pollInterval = setInterval(() => {
      voiceChat.checkIncomingCalls(session.user)
    }, 2000)

    return () => clearInterval(pollInterval)
  }, [session, voiceChat])

  useEffect(() => {
    if (!session) return

    const pollInterval = setInterval(() => {
      whiteboard.checkPendingSessions(session.user)
    }, 2000)

    return () => clearInterval(pollInterval)
  }, [session, whiteboard])

  const handleOptionChange = useCallback(
    (field) => (event) => {
      const value = event.target.value
      setConnectOptions((previous) => ({ ...previous, [field]: value }))
    },
    [],
  )

  const handleSignIn = useCallback(
    async (form) => {
      if (authPending) return
      setAuthPending(true)
      setAuthError("")
      try {
        const response = await signIn(form)
        setAuthUser(response.user)
        setToken(response.token)
      } catch (error) {
        console.error("sign in failed", error)
        setAuthError(error.message || "Unable to sign in. Please try again.")
      } finally {
        setAuthPending(false)
      }
    },
    [authPending],
  )

  const handleSignUp = useCallback(
    async (form) => {
      if (authPending) return
      setAuthPending(true)
      setAuthError("")
      try {
        const response = await signUp(form)
        setAuthUser(response.user)
        setToken(response.token)
      } catch (error) {
        console.error("sign up failed", error)
        setAuthError(error.message || "Unable to create account. Please try again.")
      } finally {
        setAuthPending(false)
      }
    },
    [authPending],
  )

  const disconnectFromLobby = useCallback(async () => {
    const active = sessionRef.current
    if (!active) return
    setSession(null)
    setUsers([])
    setMessages([])
    setSelectedUser(null)
    setPeerDetails(null)
    setPeerError("")
    setMessageText("")
    setSyncError("")
    try {
      await disconnectFromLobbyApi()
    } catch (error) {
      if (error?.status && error.status !== 404) {
        console.debug("logout error", error)
      }
    } finally {
      voiceChat.endVoiceCall?.()
      whiteboard.closeSession()
    }
  }, [voiceChat, whiteboard])

  const handleLogout = useCallback(async () => {
    await disconnectFromLobby()
    clearAuthState()
  }, [disconnectFromLobby, clearAuthState])

  const handleConnect = useCallback(
    async (event) => {
      event.preventDefault()
      if (loginPending) return
      setLoginPending(true)
      setLoginError("")
      try {
        const data = await connectToLobby(connectOptions)
        if (!data?.success) {
          throw new Error(data?.reason || "Unable to join lobby.")
        }
        setSession({ user: data.user })
        setUsers(Array.isArray(data.users) ? data.users : [])
        setMessages(dedupeMessages(Array.isArray(data.messages) ? data.messages : []))
        setMessageText("")
        setSyncError("")
        setLastRefreshed(Date.now())
      } catch (error) {
        console.error("connect failed", error)
        setLoginError(error.message || "Unable to connect. Please try again.")
      } finally {
        setLoginPending(false)
      }
    },
    [connectOptions, loginPending],
  )

  const handleSendMessage = useCallback(
    async (event) => {
      event.preventDefault()
      if (sendingMessage) return
      const current = sessionRef.current
      if (!current) return
      const draft = messageText.trim()
      if (!draft) return

      setSendingMessage(true)
      setMessageText("")
      try {
        const ack = await sendChat(draft)
        if (!sessionRef.current || sessionRef.current.user !== current.user) return
        if (ack?.accepted && ack.message) {
          setMessages((previous) => dedupeMessages([...previous, ack.message]))
          setSyncError("")
          setLastRefreshed(Date.now())
        } else {
          await refreshOnce().catch(() => {})
        }
      } catch (error) {
        console.error("send message failed", error)
        setSyncError(error.message || "Unable to send message")
        setMessageText(draft)
      } finally {
        setSendingMessage(false)
      }
    },
    [messageText, sendingMessage, refreshOnce],
  )

  const handleSelectUser = useCallback((user) => {
    setSelectedUser(user)
  }, [])

  const activeUser = sortedUsers.find((user) => user.user === session?.user)

  if (authInitializing) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-slate-950 text-slate-200">
        Validating session...
      </div>
    )
  }

  if (!authUser) {
    return (
      <AuthScreen
        onSignIn={handleSignIn}
        onSignUp={handleSignUp}
        pending={authPending}
        errorMessage={authError}
      />
    )
  }

  if (!session) {
    return (
      <LoginScreen
        authUser={authUser}
        credentials={connectOptions}
        loginError={loginError}
        loginPending={loginPending}
        onChange={handleOptionChange}
        onSubmit={handleConnect}
        onSignOut={handleLogout}
      />
    )
  }

  return (
    <>
      <IncomingWhiteboardModal
        pendingSessions={whiteboard.pendingSessions}
        currentUser={session?.user}
        onAccept={whiteboard.acceptInvitation}
        onReject={whiteboard.rejectInvitation}
      />
      <ChatShell
        activeUser={activeUser}
        apiBase={API_BASE}
        lastRefreshed={lastRefreshed}
        messages={messages}
        messagesEndRef={messagesEndRef}
        messageText={messageText}
        onLogout={handleLogout}
        onMessageChange={(event) => setMessageText(event.target.value)}
        onSendMessage={handleSendMessage}
        onSelectUser={handleSelectUser}
        peerDetails={peerDetails}
        peerError={peerError}
        peerLoading={peerLoading}
        selectedUser={selectedUser}
        sendingMessage={sendingMessage}
        session={session}
        syncError={syncError}
        timeFormatter={timeFormatter}
        users={sortedUsers}
        incomingCalls={voiceChat.incomingCalls}
        currentUserPort={activeUser?.voiceUdp}
        onAcceptCall={voiceChat.acceptIncomingCall}
        onRejectCall={voiceChat.rejectIncomingCall}
        voiceChat={voiceChat}
        whiteboard={whiteboard}
      />
    </>
  )
}

export default App
