import NumberInput from "./NumberInput"

function LoginScreen({
  authUser,
  credentials,
  loginError,
  loginPending,
  onChange,
  onSubmit,
  onSignOut,
}) {
  return (
    <div className="relative min-h-dvh w-screen overflow-hidden bg-slate-950">
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top,rgba(77,131,255,0.25),transparent_65%)]"
        aria-hidden="true"
      />
      <div className="relative z-10 mx-auto flex min-h-dvh max-w-7xl flex-col justify-center px-4 py-12">
        <div className="shadow-glass grid w-full gap-8 rounded-3xl border border-white/10 bg-slate-900/60 p-8 backdrop-blur-sm md:grid-cols-5">
          <div className="from-brand-500/80 via-brand-700/80 hidden flex-col justify-between rounded-2xl bg-linear-to-br to-slate-950 p-8 text-slate-50 md:flex">
            <div>
              <p className="text-sm tracking-[0.35em] text-white/60 uppercase">
                NexusConnect
              </p>
              <h1 className="mt-4 text-4xl font-semibold leading-tight">
                Bridge into the realtime room.
              </h1>
              <p className="mt-6 text-base text-white/80">
                You are already authenticated. Advertise optional ports to let peers reach you
                directly for file transfers or voice calls.
              </p>
            </div>
            <p className="mt-10 text-sm text-white/70">
              Disconnect to stop advertising ports. Sign out to return to the authentication screen.
            </p>
          </div>
          <div className="md:col-span-3">
            <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-8 shadow-inner">
              <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2 className="text-2xl font-semibold text-slate-50">Ready when you are</h2>
                  <p className="text-sm text-slate-400">Registry @ 8080 • NIO relay @ 8081</p>
                </div>
                <button
                  type="button"
                  onClick={onSignOut}
                  className="rounded-xl border border-white/10 px-4 py-2 text-sm font-semibold text-slate-300 transition hover:border-red-400 hover:text-red-200"
                >
                  Sign out
                </button>
              </div>
              <div className="mt-6 rounded-xl border border-white/10 bg-slate-900/60 p-4 text-sm text-slate-200">
                <p className="text-xs font-semibold uppercase tracking-[0.4em] text-slate-500">
                  Account
                </p>
                <p className="mt-2 text-base text-white">{authUser?.username}</p>
                <p className="text-sm text-slate-400">{authUser?.email}</p>
              </div>
              <p className="mt-6 text-sm text-slate-400">
                Ports are optional. Share them only if you expect to receive direct file or voice connections.
              </p>
              <form onSubmit={onSubmit} className="mt-6 space-y-6">
                <div className="grid gap-4 sm:grid-cols-2">
                  <NumberInput
                    id="fileTcp"
                    label="File transfer TCP port"
                    placeholder="Optional"
                    value={credentials.fileTcp}
                    onChange={onChange("fileTcp")}
                  />
                  <NumberInput
                    id="voiceUdp"
                    label="Voice UDP port"
                    placeholder="Optional"
                    value={credentials.voiceUdp}
                    onChange={onChange("voiceUdp")}
                  />
                </div>
                {loginError && (
                  <div className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">
                    {loginError}
                  </div>
                )}
                <button
                  type="submit"
                  disabled={loginPending}
                  className="bg-brand-500 shadow-brand-500/40 hover:bg-brand-400 focus:ring-brand-400/50 disabled:bg-brand-500/60 w-full rounded-xl px-4 py-3 text-base font-semibold text-white shadow-lg transition focus:ring-4 focus:outline-none disabled:cursor-not-allowed"
                >
                  {loginPending ? "Connecting..." : "Enter NexusConnect"}
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

export default LoginScreen
