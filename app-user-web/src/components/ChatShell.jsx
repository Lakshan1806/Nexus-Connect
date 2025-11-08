import { useMemo } from "react";
import PeerCard from "./PeerCard.jsx";
import VoicePanel from "./VoicePanel.jsx";

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
    if (!lastRefreshed) return null;
    const formatter = new Intl.DateTimeFormat(undefined, {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    return formatter.format(new Date(lastRefreshed));
  }, [lastRefreshed]);

  const peer =
    selectedUser?.user === session.user ? null : (peerDetails ?? selectedUser);

  return (
    <div className="relative min-h-screen overflow-hidden bg-slate-950">
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top,rgba(51,105,230,0.25),transparent_65%)]"
        aria-hidden="true"
      />
      <div className="relative z-10 mx-auto flex min-h-screen max-w-7xl flex-col px-4 py-10 lg:px-8">
        <header className="shadow-glass flex flex-col gap-4 rounded-3xl border border-white/5 bg-slate-900/60 p-6 backdrop-blur-sm lg:flex-row lg:items-center lg:justify-between">
          <div>
            <p className="text-brand-300/80 text-xs tracking-[0.4em] uppercase">
              NexusConnect
            </p>
            <h1 className="mt-2 text-3xl font-semibold text-slate-50">
              Global Collaboration Room
            </h1>
            <p className="text-xs text-slate-500">API {apiBase}</p>
          </div>
          {activeUser && (
            <div className="flex flex-col gap-4 rounded-2xl border border-white/5 bg-slate-950/70 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-center gap-4">
                <div className="bg-brand-500/90 flex h-12 w-12 items-center justify-center rounded-full text-xl font-semibold text-white">
                  {activeUser.user.slice(0, 2).toUpperCase()}
                </div>
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-slate-100">
                    {activeUser.user}
                  </p>
                  <p className="truncate text-xs text-slate-400">
                    {activeUser.ip || "IP hidden"} ·{" "}
                    {activeUser.viaNio ? "NIO socket" : "HTTP bridge"}
                  </p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {syncError ? (
                  <span className="rounded-full bg-amber-500/10 px-3 py-1 text-xs text-amber-200">
                    {syncError}
                  </span>
                ) : (
                  refreshedAt && (
                    <span className="rounded-full bg-emerald-500/10 px-3 py-1 text-xs text-emerald-200">
                      Synced {refreshedAt}
                    </span>
                  )
                )}
                <button
                  type="button"
                  onClick={onLogout}
                  className="hover:border-brand-400 rounded-xl border border-white/10 bg-transparent px-3 py-2 text-xs font-semibold text-slate-300 transition hover:text-white"
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
              <h2 className="text-sm font-semibold tracking-[0.2em] text-slate-400 uppercase">
                Active peers
              </h2>
              <p className="text-xs text-slate-500">{users.length} online</p>
            </div>
            <div className="space-y-2 overflow-y-auto pr-1">
              {users.map((user) => {
                const isSelected = selectedUser?.user === user.user;
                const isSelf = user.user === session.user;
                return (
                  <button
                    key={user.user}
                    type="button"
                    onClick={() => onSelectUser(user)}
                    className={`flex w-full items-center justify-between rounded-2xl border px-4 py-3 text-left transition ${
                      isSelected
                        ? "border-brand-400 bg-brand-500/10 text-brand-100"
                        : "hover:border-brand-400 border-white/10 bg-slate-950/60 text-slate-300 hover:text-white"
                    }`}
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold">
                        {user.user}
                      </p>
                      <p className="truncate text-xs text-slate-400">
                        {isSelf ? "You · " : ""}
                        {user.viaNio ? "NIO socket" : "HTTP bridge"}
                      </p>
                    </div>
                    <span className="ml-3 flex h-8 w-8 items-center justify-center rounded-full bg-white/10 text-xs font-semibold">
                      {user.user.slice(0, 2).toUpperCase()}
                    </span>
                  </button>
                );
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
            {selectedUser && (
              <VoicePanel
                apiBase={apiBase}
                currentUser={session}
                selectedUser={selectedUser}
                peerDetails={peerDetails}
                localVoicePort={activeUser?.voiceUdp}
                onVoiceSessionStart={(sessionId) => {
                  console.log("Voice session started:", sessionId);
                }}
                onVoiceSessionEnd={() => {
                  console.log("Voice session ended");
                }}
              />
            )}
          </aside>

          <section className="flex min-h-[60vh] flex-1 flex-col rounded-3xl border border-white/5 bg-slate-900/60 shadow-inner backdrop-blur-sm">
            <div className="flex items-center justify-between border-b border-white/5 px-6 py-4">
              <div>
                <p className="text-sm font-semibold text-slate-100">
                  {selectedUser
                    ? selectedUser.user === session.user
                      ? "Everyone"
                      : selectedUser.user
                    : "Everyone"}
                </p>
                <p className="text-xs text-slate-400">
                  Messages refresh automatically every few seconds.
                </p>
              </div>
              {syncError && (
                <span className="rounded-full bg-amber-500/10 px-3 py-1 text-xs text-amber-200">
                  {syncError}
                </span>
              )}
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto px-6 py-6">
              {messages.length === 0 && (
                <div className="flex h-full min-h-[40vh] items-center justify-center text-sm text-slate-500">
                  No messages yet. Start the conversation!
                </div>
              )}
              {messages.map((message) => {
                const isSelf = message.from === session.user;
                return (
                  <div
                    key={`${message.timestampSeconds}-${message.from}-${message.text}`}
                    className={`flex w-full ${isSelf ? "justify-end" : "justify-start"}`}
                  >
                    <div
                      className={`max-w-[75%] rounded-3xl px-4 py-3 text-sm shadow ${
                        isSelf
                          ? "bg-brand-500 shadow-brand-500/30 text-white"
                          : "bg-slate-800/80 text-slate-100 shadow-slate-900/40"
                      }`}
                    >
                      <div className="flex items-center justify-between gap-3 text-xs">
                        <span
                          className={`font-semibold ${isSelf ? "text-white/90" : "text-brand-200/80"}`}
                        >
                          {isSelf ? "You" : message.from}
                        </span>
                        <span className="text-white/70">
                          {message.timestampSeconds
                            ? timeFormatter.format(
                                new Date(message.timestampSeconds * 1000),
                              )
                            : ""}
                        </span>
                      </div>
                      <p className="mt-2 leading-relaxed whitespace-pre-line">
                        {message.text}
                      </p>
                    </div>
                  </div>
                );
              })}
              <div ref={messagesEndRef} />
            </div>

            <form
              onSubmit={onSendMessage}
              className="border-t border-white/5 px-6 py-4"
            >
              <div className="focus-within:border-brand-400 flex items-end gap-3 rounded-2xl border border-white/10 bg-slate-950/70 px-4 py-3 shadow-inner">
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
                  className="bg-brand-500 hover:bg-brand-400 disabled:bg-brand-500/60 inline-flex shrink-0 items-center gap-2 rounded-xl px-4 py-2 text-sm font-semibold text-white transition disabled:cursor-not-allowed"
                >
                  <span>{sendingMessage ? "Sending…" : "Send"}</span>
                  <svg
                    aria-hidden="true"
                    xmlns="http://www.w3.org/2000/svg"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    className="h-4 w-4"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="m4.5 12 15-7-4.5 7 4.5 7-15-7z"
                    />
                  </svg>
                </button>
              </div>
            </form>
          </section>
        </main>
      </div>
    </div>
  );
}

export default ChatShell;