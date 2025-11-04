import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  API_BASE,
  fetchPeerDetails,
  fetchSnapshot,
  login as loginRequest,
  logout as logoutRequest,
  sendChat,
} from "./api.js";
import LoginScreen from "./components/LoginScreen.jsx";
import ChatShell from "./components/ChatShell.jsx";

const POLL_INTERVAL_MS = 4000;

const dedupeMessages = (messages) => {
  const map = new Map();
  for (const message of messages ?? []) {
    if (!message) continue;
    const key = `${message.timestampSeconds ?? 0}-${message.from ?? ""}-${message.text ?? ""}`;
    map.set(key, message);
  }
  return Array.from(map.values()).sort(
    (a, b) => (a?.timestampSeconds ?? 0) - (b?.timestampSeconds ?? 0),
  );
};

const portValue = (value) => {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const parsed = Number.parseInt(trimmed, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
};

function App() {
  const [credentials, setCredentials] = useState({
    user: "",
    pass: "",
    fileTcp: "",
    voiceUdp: "",
  });
  const [loginPending, setLoginPending] = useState(false);
  const [loginError, setLoginError] = useState("");
  const [session, setSession] = useState(null);
  const [users, setUsers] = useState([]);
  const [messages, setMessages] = useState([]);
  const [selectedUser, setSelectedUser] = useState(null);
  const [peerDetails, setPeerDetails] = useState(null);
  const [peerError, setPeerError] = useState("");
  const [peerLoading, setPeerLoading] = useState(false);
  const [messageText, setMessageText] = useState("");
  const [sendingMessage, setSendingMessage] = useState(false);
  const [syncError, setSyncError] = useState("");
  const [lastRefreshed, setLastRefreshed] = useState(null);

  const sessionRef = useRef(session);
  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  const messagesEndRef = useRef(null);
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const timeFormatter = useMemo(
    () =>
      new Intl.DateTimeFormat(undefined, {
        hour: "2-digit",
        minute: "2-digit",
      }),
    [],
  );

  const sortedUsers = useMemo(
    () => [...users].sort((a, b) => a.user.localeCompare(b.user)),
    [users],
  );

  useEffect(() => {
    if (!session) {
      setSelectedUser(null);
      return;
    }
    setSelectedUser((previous) => {
      if (previous) {
        const stillOnline = sortedUsers.find(
          (user) => user.user === previous.user,
        );
        if (stillOnline) return stillOnline;
      }
      const self = sortedUsers.find((user) => user.user === session.user);
      if (self) return self;
      return sortedUsers[0] ?? null;
    });
  }, [session, sortedUsers]);

  const refreshOnce = useCallback(async () => {
    const currentSession = sessionRef.current;
    if (!currentSession) return;
    const currentUser = currentSession.user;
    try {
      const { users: freshUsers, messages: freshMessages } =
        await fetchSnapshot();
      if (!sessionRef.current || sessionRef.current.user !== currentUser)
        return;
      setUsers(freshUsers);
      setMessages(dedupeMessages(freshMessages));
      setSyncError("");
      setLastRefreshed(Date.now());
    } catch (error) {
      if (sessionRef.current && sessionRef.current.user === currentUser) {
        setSyncError("Connection unstable. Trying to resync…");
      }
      throw error;
    }
  }, []);

  useEffect(() => {
    if (!session) return undefined;
    let cancelled = false;

    const tick = async () => {
      try {
        await refreshOnce();
      } catch (error) {
        if (cancelled) return;
        console.debug("refresh failed", error);
      }
    };

    tick();
    const interval = window.setInterval(tick, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [session, refreshOnce]);

  useEffect(() => {
    if (!session || !selectedUser || selectedUser.user === session.user) {
      setPeerDetails(null);
      setPeerError("");
      setPeerLoading(false);
      return;
    }
    let active = true;
    setPeerLoading(true);
    setPeerError("");
    fetchPeerDetails(selectedUser.user)
      .then((details) => {
        if (!active) return;
        setPeerDetails(details);
        if (!details) {
          setPeerError("Peer is offline or did not publish details yet.");
        }
      })
      .catch((error) => {
        if (!active) return;
        setPeerDetails(null);
        setPeerError(error.message || "Unable to load peer details");
      })
      .finally(() => {
        if (active) {
          setPeerLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, [session, selectedUser]);

  const handleInputChange = useCallback(
    (field) => (event) => {
      const value = event.target.value;
      setCredentials((previous) => ({ ...previous, [field]: value }));
    },
    [],
  );

  const handleLogin = useCallback(
    async (event) => {
      event.preventDefault();
      if (loginPending) return;
      const user = credentials.user.trim();
      const pass = credentials.pass.trim();
      if (!user || !pass) {
        setLoginError("Username and password are required");
        return;
      }
      setLoginPending(true);
      setLoginError("");
      try {
        const payload = { user, pass };
        const fileTcp = portValue(credentials.fileTcp);
        if (fileTcp !== undefined) payload.fileTcp = fileTcp;
        const voiceUdp = portValue(credentials.voiceUdp);
        if (voiceUdp !== undefined) payload.voiceUdp = voiceUdp;

        const data = await loginRequest(payload);
        if (!data?.success) {
          throw new Error(data?.reason || "Login failed");
        }
        setSession({ user: data.user });
        setUsers(Array.isArray(data.users) ? data.users : []);
        setMessages(
          dedupeMessages(Array.isArray(data.messages) ? data.messages : []),
        );
        setMessageText("");
        setSyncError("");
        setLastRefreshed(Date.now());
        setCredentials((previous) => ({ ...previous, pass: "" }));
      } catch (error) {
        console.error("login failed", error);
        setLoginError(error.message || "Unable to login. Please try again.");
      } finally {
        setLoginPending(false);
      }
    },
    [credentials, loginPending],
  );

  const handleLogout = useCallback(async () => {
    const active = sessionRef.current;
    if (!active) return;
    const user = active.user;
    setSession(null);
    setUsers([]);
    setMessages([]);
    setSelectedUser(null);
    setPeerDetails(null);
    setPeerError("");
    setMessageText("");
    setSyncError("");
    try {
      await logoutRequest(user);
    } catch (error) {
      console.debug("logout error", error);
    }
  }, []);

  const handleSendMessage = useCallback(
    async (event) => {
      event.preventDefault();
      if (sendingMessage) return;
      const current = sessionRef.current;
      if (!current) return;
      const draft = messageText.trim();
      if (!draft) return;

      setSendingMessage(true);
      setMessageText("");
      try {
        const ack = await sendChat({ user: current.user, text: draft });
        if (!sessionRef.current || sessionRef.current.user !== current.user)
          return;
        if (ack?.accepted && ack.message) {
          setMessages((previous) => dedupeMessages([...previous, ack.message]));
          setSyncError("");
          setLastRefreshed(Date.now());
        } else {
          await refreshOnce().catch(() => {});
        }
      } catch (error) {
        console.error("send message failed", error);
        setSyncError(error.message || "Unable to send message");
        setMessageText(draft);
      } finally {
        setSendingMessage(false);
      }
    },
    [messageText, sendingMessage, refreshOnce],
  );

  const handleSelectUser = useCallback((user) => {
    setSelectedUser(user);
  }, []);

  const activeUser = sortedUsers.find((user) => user.user === session?.user);

  if (!session) {
    return (
      <LoginScreen
        credentials={credentials}
        loginError={loginError}
        loginPending={loginPending}
        onChange={handleInputChange}
        onSubmit={handleLogin}
      />
    );
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
  );
}

export default App;
