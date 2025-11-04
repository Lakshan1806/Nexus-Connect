import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  API_BASE,
  fetchPeerDetails,
  fetchSnapshot,
  login as loginRequest,
  logout as logoutRequest,
  sendChat,
} from './api.js'

const POLL_INTERVAL_MS = 4000

const dedupeMessages = (messages) => {
  const map = new Map()
  for (const message of messages ?? []) {
    if (!message) continue
    const key = `${message.timestampSeconds ?? 0}-${message.from ?? ''}-${message.text ?? ''}`
    map.set(key, message)
  }
  return Array.from(map.values()).sort(
    (a, b) => (a?.timestampSeconds ?? 0) - (b?.timestampSeconds ?? 0),
  )
}

const portValue = (value) => {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  const parsed = Number.parseInt(trimmed, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

function App() {
  const [credentials, setCredentials] = useState({ user: '', pass: '', fileTcp: '', voiceUdp: '' })
  const [loginPending, setLoginPending] = useState(false)
  const [loginError, setLoginError] = useState('')
  const [session, setSession] = useState(null)
  const [users, setUsers] = useState([])
  const [messages, setMessages] = useState([])
  const [selectedUser, setSelectedUser] = useState(null)
  const [peerDetails, setPeerDetails] = useState(null)
  const [peerError, setPeerError] = useState('')
  const [peerLoading, setPeerLoading] = useState(false)
  const [messageText, setMessageText] = useState('')
  const [sendingMessage, setSendingMessage] = useState(false)
  const [syncError, setSyncError] = useState('')
  const [lastRefreshed, setLastRefreshed] = useState(null)

  const sessionRef = useRef(session)
  useEffect(() => {
    sessionRef.current = session
  }, [session])

  const messagesEndRef = useRef(null)
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const timeFormatter = useMemo(
    () =>
      new Intl.DateTimeFormat(undefined, {
        hour: '2-digit',
        minute: '2-digit',
      }),
    [],
  )

  const sortedUsers = useMemo(
    () => [...users].sort((a, b) => a.user.localeCompare(b.user)),
    [users],
  )

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
      setSyncError('')
      setLastRefreshed(Date.now())
    } catch (error) {
      if (sessionRef.current && sessionRef.current.user === currentUser) {
        setSyncError('Connection unstable. Trying to resync…')
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
        console.debug('refresh failed', error)
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
      setPeerError('')
      setPeerLoading(false)
      return
    }
    let active = true
    setPeerLoading(true)
    setPeerError('')
    fetchPeerDetails(selectedUser.user)
      .then((details) => {
        if (!active) return
        setPeerDetails(details)
        if (!details) {
          setPeerError('Peer is offline or did not publish details yet.')
        }
      })
      .catch((error) => {
        if (!active) return
        setPeerDetails(null)
        setPeerError(error.message || 'Unable to load peer details')
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

  const handleInputChange = useCallback(
    (field) => (event) => {
      const value = event.target.value
      setCredentials((previous) => ({ ...previous, [field]: value }))
    },
    [],
  )

  const handleLogin = useCallback(
    async (event) => {
      event.preventDefault()
      if (loginPending) return
      const user = credentials.user.trim()
      const pass = credentials.pass.trim()
      if (!user || !pass) {
        setLoginError('Username and password are required')
        return
      }
      setLoginPending(true)
      setLoginError('')
      try {
        const payload = { user, pass }
        const fileTcp = portValue(credentials.fileTcp)
        if (fileTcp !== undefined) payload.fileTcp = fileTcp
        const voiceUdp = portValue(credentials.voiceUdp)
        if (voiceUdp !== undefined) payload.voiceUdp = voiceUdp

        const data = await loginRequest(payload)
        if (!data?.success) {
          throw new Error(data?.reason || 'Login failed')
        }
        setSession({ user: data.user })
        setUsers(Array.isArray(data.users) ? data.users : [])
        setMessages(dedupeMessages(Array.isArray(data.messages) ? data.messages : []))
        setMessageText('')
        setSyncError('')
        setLastRefreshed(Date.now())
        setCredentials((previous) => ({ ...previous, pass: '' }))
      } catch (error) {
        console.error('login failed', error)
        setLoginError(error.message || 'Unable to login. Please try again.')
      } finally {
        setLoginPending(false)
      }
    },
    [credentials, loginPending],
  )

  const handleLogout = useCallback(async () => {
    const active = sessionRef.current
    if (!active) return
    const user = active.user
    setSession(null)
    setUsers([])
    setMessages([])
    setSelectedUser(null)
    setPeerDetails(null)
    setPeerError('')
    setMessageText('')
    setSyncError('')
    try {
      await logoutRequest(user)
    } catch (error) {
      console.debug('logout error', error)
    }
  }, [])

  const handleSendMessage = useCallback(
    async (event) => {
      event.preventDefault()
      if (sendingMessage) return
      const current = sessionRef.current
      if (!current) return
      const draft = messageText.trim()
      if (!draft) return

      setSendingMessage(true)
      setMessageText('')
      try {
        const ack = await sendChat({ user: current.user, text: draft })
        if (!sessionRef.current || sessionRef.current.user !== current.user) return
        if (ack?.accepted && ack.message) {
          setMessages((previous) => dedupeMessages([...previous, ack.message]))
          setSyncError('')
          setLastRefreshed(Date.now())
        } else {
          await refreshOnce().catch(() => {})
        }
      } catch (error) {
        console.error('send message failed', error)
        setSyncError(error.message || 'Unable to send message')
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

  if (!session) {
    return (
      <LoginScreen
        credentials={credentials}
        loginError={loginError}
        loginPending={loginPending}
        onChange={handleInputChange}
        onSubmit={handleLogin}
      />
    )
  }

  return (
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
    />
  )
}

function LoginScreen({ credentials, loginError, loginPending, onChange, onSubmit }) {
  return (
    <div className="relative min-h-screen overflow-hidden bg-slate-950">
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top,_rgba(77,131,255,0.25),_transparent_65%)]"
        aria-hidden="true"
      />
      <div className="relative z-10 mx-auto flex min-h-screen max-w-6xl flex-col justify-center px-4 py-12">
        <div className="grid gap-8 rounded-3xl border border-white/10 bg-slate-900/60 p-8 shadow-glass backdrop-blur-sm md:grid-cols-5">
          <div className="hidden flex-col justify-between rounded-2xl bg-gradient-to-br from-brand-500/80 via-brand-700/80 to-slate-950 p-8 text-slate-50 md:flex">
            <div>
              <p className="text-sm uppercase tracking-[0.35em] text-white/60">NexusConnect</p>
              <h1 className="mt-4 text-4xl font-semibold leading-tight">
                Hybrid collaboration at the speed of conversation.
              </h1>
              <p className="mt-6 text-base text-white/80">
                Log in to the registry server to join the global room, discover peers, and hand off the heavy lifting to direct
                connections.
              </p>
            </div>
            <p className="mt-10 text-sm text-white/70">
              Use the credentials issued by the registry administrator. Authentication is handled entirely by the NIO server.
            </p>
          </div>
          <div className="md:col-span-3">
            <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-8 shadow-inner">
              <div className="flex items-center justify-between">
                <h2 className="text-2xl font-semibold text-slate-50">Welcome back</h2>
                <span className="text-sm text-slate-400">Registry @ 8080 · NIO relay @ 8081</span>
              </div>
              <p className="mt-2 text-sm text-slate-400">
                Use your assigned credentials to enter the NexusConnect lobby. Ports are optional and help peers reach you directly for
                file or voice sessions.
              </p>
              <form onSubmit={onSubmit} className="mt-8 space-y-6">
                <div className="space-y-2">
                  <label className="text-sm font-medium text-slate-200" htmlFor="username">
                    Username
                  </label>
                  <input
                    id="username"
                    type="text"
                    autoComplete="username"
                    value={credentials.user}
                    onChange={onChange('user')}
                    className="w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-400/60"
                    placeholder="e.g. lakshan"
                  />
                </div>
                <div className="space-y-2">
                  <label className="text-sm font-medium text-slate-200" htmlFor="password">
                    Password
                  </label>
                  <input
                    id="password"
                    type="password"
                    autoComplete="current-password"
                    value={credentials.pass}
                    onChange={onChange('pass')}
                    className="w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-400/60"
                    placeholder="••••••"
                  />
                </div>
                <div className="grid gap-4 sm:grid-cols-2">
                  <NumberInput
                    id="fileTcp"
                    label="File transfer TCP port"
                    placeholder="Optional"
                    value={credentials.fileTcp}
                    onChange={onChange('fileTcp')}
                  />
                  <NumberInput
                    id="voiceUdp"
                    label="Voice UDP port"
                    placeholder="Optional"
                    value={credentials.voiceUdp}
                    onChange={onChange('voiceUdp')}
                  />
                </div>
                {loginError && (
                  <div className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">{loginError}</div>
                )}
                <button
                  type="submit"
                  disabled={loginPending}
                  className="w-full rounded-xl bg-brand-500 px-4 py-3 text-base font-semibold text-white shadow-lg shadow-brand-500/40 transition hover:bg-brand-400 focus:outline-none focus:ring-4 focus:ring-brand-400/50 disabled:cursor-not-allowed disabled:bg-brand-500/60"
                >
                  {loginPending ? 'Signing you in…' : 'Enter NexusConnect'}
                </button>
              </form>
            </div>
            <p className="mt-6 text-center text-xs text-slate-500">
              Need the socket server instead? Connect via TCP to port 8081 and speak the colon-delimited protocol.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

function NumberInput({ id, label, placeholder, value, onChange }) {
  return (
    <div className="space-y-2">
      <label className="text-sm font-medium text-slate-200" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        type="number"
        inputMode="numeric"
        min="0"
        value={value}
        onChange={onChange}
        className="w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:border-brand-400 focus:outline-none focus:ring-2 focus:ring-brand-400/60"
        placeholder={placeholder}
      />
    </div>
  )
}

function ChatShell({
  activeUser,
  apiBase,
  lastRefreshed,
  messages,
  messagesEndRef,
  messageText,
  onLogout,
  onMessageChange,
  onSendMessage,
  onSelectUser,
  peerDetails,
  peerError,
  peerLoading,
  selectedUser,
  sendingMessage,
  session,
  syncError,
  timeFormatter,
  users,
}) {
  const refreshedAt = useMemo(() => {
    if (!lastRefreshed) return null
    const formatter = new Intl.DateTimeFormat(undefined, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
    return formatter.format(new Date(lastRefreshed))
  }, [lastRefreshed])

  const peer = selectedUser?.user === session.user ? null : peerDetails ?? selectedUser

  return (
    <div className="relative min-h-screen overflow-hidden bg-slate-950">
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top,_rgba(51,105,230,0.25),_transparent_65%)]"
        aria-hidden="true"
      />
      <div className="relative z-10 mx-auto flex min-h-screen max-w-7xl flex-col px-4 py-10 lg:px-8">
        <header className="flex flex-col gap-4 rounded-3xl border border-white/5 bg-slate-900/60 p-6 shadow-glass backdrop-blur-sm lg:flex-row lg:items-center lg:justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.4em] text-brand-300/80">NexusConnect</p>
            <h1 className="mt-2 text-3xl font-semibold text-slate-50">Global Collaboration Room</h1>
            <p className="text-xs text-slate-500">API {apiBase}</p>
          </div>
          {activeUser && (
            <div className="flex flex-col gap-4 rounded-2xl border border-white/5 bg-slate-950/70 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-4">
                <div className="flex h-12 w-12 items-center justify-center rounded-full bg-brand-500/90 text-xl font-semibold text-white">
                  {activeUser.user.slice(0, 2).toUpperCase()}
                </div>
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-slate-100">{activeUser.user}</p>
                  <p className="truncate text-xs text-slate-400">
                    {activeUser.ip || 'IP hidden'} · {activeUser.viaNio ? 'NIO socket' : 'HTTP bridge'}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {syncError ? (
                  <span className="rounded-full bg-amber-500/10 px-3 py-1 text-xs text-amber-200">{syncError}</span>
                ) : (
                  refreshedAt && <span className="rounded-full bg-emerald-500/10 px-3 py-1 text-xs text-emerald-200">Synced {refreshedAt}</span>
                )}
                <button
                  type="button"
                  onClick={onLogout}
                  className="rounded-xl border border-white/10 bg-transparent px-3 py-2 text-xs font-semibold text-slate-300 transition hover:border-brand-400 hover:text-white"
                >
                  Log out
                </button>
              </div>
            </div>
          )}
        </header>

        <main className="mt-8 flex flex-1 flex-col gap-6 lg:flex-row">
          <aside className="flex w-full flex-col gap-6 rounded-3xl border border-white/5 bg-slate-900/60 p-6 shadow-inner backdrop-blur-sm lg:w-80">
            <div>
              <h2 className="text-sm font-semibold uppercase tracking-[0.2em] text-slate-400">Active peers</h2>
              <p className="text-xs text-slate-500">{users.length} online</p>
            </div>
            <div className="space-y-2 overflow-y-auto pr-1">
              {users.map((user) => {
                const isSelected = selectedUser?.user === user.user
                const isSelf = user.user === session.user
                return (
                  <button
                    key={user.user}
                    type="button"
                    onClick={() => onSelectUser(user)}
                    className={`flex w-full items-center justify-between rounded-2xl border px-4 py-3 text-left transition ${
                      isSelected
                        ? 'border-brand-400 bg-brand-500/10 text-brand-100'
                        : 'border-white/10 bg-slate-950/60 text-slate-300 hover:border-brand-400 hover:text-white'
                    }`}
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold">{user.user}</p>
                      <p className="truncate text-xs text-slate-400">
                        {isSelf ? 'You · ' : ''}
                        {user.viaNio ? 'NIO socket' : 'HTTP bridge'}
                      </p>
                    </div>
                    <span className="ml-3 flex h-8 w-8 items-center justify-center rounded-full bg-white/10 text-xs font-semibold">
                      {user.user.slice(0, 2).toUpperCase()}
                    </span>
                  </button>
                )
              })}
            </div>
            {selectedUser && (
              <PeerCard
                peer={peer}
                peerError={peerError}
                peerLoading={peerLoading}
                selectedUser={selectedUser}
                session={session}
              />
            )}
          </aside>

          <section className="flex min-h-[60vh] flex-1 flex-col rounded-3xl border border-white/5 bg-slate-900/60 shadow-inner backdrop-blur-sm">
            <div className="flex items-center justify-between border-b border-white/5 px-6 py-4">
              <div>
                <p className="text-sm font-semibold text-slate-100">
                  {selectedUser ? (selectedUser.user === session.user ? 'Everyone' : selectedUser.user) : 'Everyone'}
                </p>
                <p className="text-xs text-slate-400">Messages refresh automatically every few seconds.</p>
              </div>
              {syncError && <span className="rounded-full bg-amber-500/10 px-3 py-1 text-xs text-amber-200">{syncError}</span>}
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto px-6 py-6">
              {messages.length === 0 && (
                <div className="flex h-full min-h-[40vh] items-center justify-center text-sm text-slate-500">
                  No messages yet. Start the conversation!
                </div>
              )}
              {messages.map((message) => {
                const isSelf = message.from === session.user
                return (
                  <div
                    key={`${message.timestampSeconds}-${message.from}-${message.text}`}
                    className={`flex w-full ${isSelf ? 'justify-end' : 'justify-start'}`}
                  >
                    <div
                      className={`max-w-[75%] rounded-3xl px-4 py-3 text-sm shadow ${
                        isSelf ? 'bg-brand-500 text-white shadow-brand-500/30' : 'bg-slate-800/80 text-slate-100 shadow-slate-900/40'
                      }`}
                    >
                      <div className="flex items-center justify-between gap-3 text-xs">
                        <span className={`font-semibold ${isSelf ? 'text-white/90' : 'text-brand-200/80'}`}>
                          {isSelf ? 'You' : message.from}
                        </span>
                        <span className="text-white/70">
                          {message.timestampSeconds
                            ? timeFormatter.format(new Date(message.timestampSeconds * 1000))
                            : ''}
                        </span>
                      </div>
                      <p className="mt-2 whitespace-pre-line leading-relaxed">{message.text}</p>
                    </div>
                  </div>
                )
              })}
              <div ref={messagesEndRef} />
            </div>

            <form onSubmit={onSendMessage} className="border-t border-white/5 px-6 py-4">
              <div className="flex items-end gap-3 rounded-2xl border border-white/10 bg-slate-950/70 px-4 py-3 shadow-inner focus-within:border-brand-400">
                <textarea
                  rows={1}
                  value={messageText}
                  onChange={onMessageChange}
                  placeholder="Type a message to everyone…"
                  className="h-12 w-full resize-none bg-transparent text-sm text-slate-100 placeholder:text-slate-500 focus:outline-none"
                />
                <button
                  type="submit"
                  disabled={sendingMessage || !messageText.trim()}
                  className="inline-flex shrink-0 items-center gap-2 rounded-xl bg-brand-500 px-4 py-2 text-sm font-semibold text-white transition hover:bg-brand-400 disabled:cursor-not-allowed disabled:bg-brand-500/60"
                >
                  <span>{sendingMessage ? 'Sending…' : 'Send'}</span>
                  <svg
                    aria-hidden="true"
                    xmlns="http://www.w3.org/2000/svg"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    className="h-4 w-4"
                  >
                    <path strokeLinecap="round" strokeLinejoin="round" d="m4.5 12 15-7-4.5 7 4.5 7-15-7z" />
                  </svg>
                </button>
              </div>
            </form>
          </section>
        </main>
      </div>
    </div>
  )
}

function PeerCard({ peer, peerError, peerLoading, selectedUser, session }) {
  const isSelf = selectedUser.user === session.user
  return (
    <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-4 text-sm text-slate-300">
      <h3 className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-400">Peer card</h3>
      <p className="mt-2 text-base font-semibold text-slate-100">{selectedUser.user}</p>
      {isSelf ? (
        <p className="mt-2 text-xs text-slate-500">This is you. Advertise your ports during login so peers can reach you directly.</p>
      ) : peerLoading ? (
        <p className="mt-2 text-xs text-slate-500">Fetching live peer details…</p>
      ) : peerError ? (
        <p className="mt-2 text-xs text-amber-300">{peerError}</p>
      ) : peer ? (
        <dl className="mt-3 space-y-2">
          <PeerRow label="IP" value={peer.ip || 'Not shared'} />
          <PeerRow label="File TCP" value={peer.fileTcp > 0 ? peer.fileTcp : 'Not advertised'} />
          <PeerRow label="Voice UDP" value={peer.voiceUdp > 0 ? peer.voiceUdp : 'Not advertised'} />
          <PeerRow label="Gateway" value={peer.viaNio ? 'Direct NIO socket' : 'HTTP bridge'} />
        </dl>
      ) : (
        <p className="mt-2 text-xs text-slate-500">No peer details available yet.</p>
      )}
    </div>
  )
}

function PeerRow({ label, value }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="text-sm text-slate-200">{value}</dd>
    </div>
  )
}

export default App;
