import NumberInput from "./NumberInput";

function LoginScreen({
  credentials,
  loginError,
  loginPending,
  onChange,
  onSubmit,
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
              <h1 className="mt-4 text-4xl leading-tight font-semibold">
                Hybrid collaboration at the speed of conversation.
              </h1>
              <p className="mt-6 text-base text-white/80">
                Log in to the registry server to join the global room, discover
                peers, and hand off the heavy lifting to direct connections.
              </p>
            </div>
            <p className="mt-10 text-sm text-white/70">
              Use the credentials issued by the registry administrator.
              Authentication is handled entirely by the NIO server.
            </p>
          </div>
          <div className="md:col-span-3">
            <div className="rounded-2xl border border-white/10 bg-slate-950/70 p-8 shadow-inner">
              <div className="flex items-center justify-between">
                <h2 className="text-2xl font-semibold text-slate-50">
                  Welcome back
                </h2>
                <span className="text-sm text-slate-400">
                  Registry @ 8080 · NIO relay @ 8081
                </span>
              </div>
              <p className="mt-2 text-sm text-slate-400">
                Use your assigned credentials to enter the NexusConnect lobby.
                Ports are optional and help peers reach you directly for file or
                voice sessions.
              </p>
              <form onSubmit={onSubmit} className="mt-8 space-y-6">
                <div className="space-y-2">
                  <label
                    className="text-sm font-medium text-slate-200"
                    htmlFor="username"
                  >
                    Username
                  </label>
                  <input
                    id="username"
                    type="text"
                    autoComplete="username"
                    value={credentials.user}
                    onChange={onChange("user")}
                    className="focus:border-brand-400 focus:ring-brand-400/60 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="e.g. lakshan"
                  />
                </div>
                <div className="space-y-2">
                  <label
                    className="text-sm font-medium text-slate-200"
                    htmlFor="password"
                  >
                    Password
                  </label>
                  <input
                    id="password"
                    type="password"
                    autoComplete="current-password"
                    value={credentials.pass}
                    onChange={onChange("pass")}
                    className="focus:border-brand-400 focus:ring-brand-400/60 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="••••••"
                  />
                </div>
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
                  {loginPending ? "Signing you in…" : "Enter NexusConnect"}
                </button>
              </form>
            </div>
            <p className="mt-6 text-center text-xs text-slate-500">
              Need the socket server instead? Connect via TCP to port 8081 and
              speak the colon-delimited protocol.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

export default LoginScreen;
