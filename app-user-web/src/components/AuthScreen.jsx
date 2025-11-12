import { useState } from 'react'

function AuthScreen({ onSignIn, onSignUp, pending, errorMessage }) {
  const [mode, setMode] = useState('signin')
  const [signInForm, setSignInForm] = useState({ email: '', password: '' })
  const [signUpForm, setSignUpForm] = useState({ name: '', email: '', password: '' })

  const handleSignInSubmit = (event) => {
    event.preventDefault()
    if (pending) return
    onSignIn?.(signInForm)
  }

  const handleSignUpSubmit = (event) => {
    event.preventDefault()
    if (pending) return
    onSignUp?.(signUpForm)
  }

  return (
    <div className="relative min-h-dvh w-screen overflow-hidden bg-slate-950">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_top,rgba(77,131,255,0.25),transparent_65%)]"
      />
      <div className="relative z-10 mx-auto flex min-h-dvh max-w-5xl flex-col justify-center px-4 py-12">
        <div className="shadow-glass grid w-full gap-8 rounded-3xl border border-white/10 bg-slate-900/60 p-8 backdrop-blur-sm md:grid-cols-5">
          <div className="from-brand-500/80 via-brand-700/80 hidden flex-col justify-between rounded-2xl bg-linear-to-br to-slate-950 p-8 text-slate-50 md:col-span-2 md:flex">
            <div>
              <p className="text-sm tracking-[0.35em] text-white/60 uppercase">NexusConnect</p>
              <h1 className="mt-4 text-4xl font-semibold leading-tight">Realtime collaboration that starts with trust.</h1>
              <p className="mt-6 text-base text-white/80">
                Create an account to unlock secure voice, chat, file sharing, and whiteboard sessions with your peers.
              </p>
            </div>
            <p className="mt-10 text-sm text-white/70">Accounts are stored in MySQL and protected by JWT-based sessions.</p>
          </div>
          <div className="md:col-span-3">
            <div className="mb-6 flex rounded-2xl border border-white/10 bg-slate-950/50 p-1">
              <button
                type="button"
                onClick={() => setMode('signin')}
                className={`flex-1 rounded-xl px-4 py-2 text-sm font-semibold transition ${
                  mode === 'signin' ? 'bg-white text-slate-900' : 'text-slate-400 hover:text-white'
                }`}
              >
                Sign In
              </button>
              <button
                type="button"
                onClick={() => setMode('signup')}
                className={`flex-1 rounded-xl px-4 py-2 text-sm font-semibold transition ${
                  mode === 'signup' ? 'bg-white text-slate-900' : 'text-slate-400 hover:text-white'
                }`}
              >
                Sign Up
              </button>
            </div>
            {mode === 'signin' ? (
              <form onSubmit={handleSignInSubmit} className="rounded-2xl border border-white/10 bg-slate-950/70 p-8 shadow-inner space-y-6">
                <div>
                  <label className="text-sm font-medium text-slate-200" htmlFor="signin-email">
                    Email
                  </label>
                  <input
                    id="signin-email"
                    type="email"
                    value={signInForm.email}
                    onChange={(event) => setSignInForm((prev) => ({ ...prev, email: event.target.value }))}
                    className="focus:border-brand-400 focus:ring-brand-400/60 mt-2 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="you@example.com"
                    autoComplete="email"
                  />
                </div>
                <div>
                  <label className="text-sm font-medium text-slate-200" htmlFor="signin-password">
                    Password
                  </label>
                  <input
                    id="signin-password"
                    type="password"
                    value={signInForm.password}
                    onChange={(event) => setSignInForm((prev) => ({ ...prev, password: event.target.value }))}
                    className="focus:border-brand-400 focus:ring-brand-400/60 mt-2 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="••••••••"
                    autoComplete="current-password"
                  />
                </div>
                {errorMessage && (
                  <div className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">{errorMessage}</div>
                )}
                <button
                  type="submit"
                  disabled={pending}
                  className="bg-brand-500 shadow-brand-500/40 hover:bg-brand-400 focus:ring-brand-400/50 disabled:bg-brand-500/60 w-full rounded-xl px-4 py-3 text-base font-semibold text-white shadow-lg transition focus:ring-4 focus:outline-none disabled:cursor-not-allowed"
                >
                  {pending ? 'Signing in...' : 'Sign In'}
                </button>
              </form>
            ) : (
              <form onSubmit={handleSignUpSubmit} className="rounded-2xl border border-white/10 bg-slate-950/70 p-8 shadow-inner space-y-6">
                <div>
                  <label className="text-sm font-medium text-slate-200" htmlFor="signup-name">
                    Name / Handle
                  </label>
                  <input
                    id="signup-name"
                    type="text"
                    value={signUpForm.name}
                    onChange={(event) => setSignUpForm((prev) => ({ ...prev, name: event.target.value }))}
                    className="focus:border-brand-400 focus:ring-brand-400/60 mt-2 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="lakshan"
                    autoComplete="username"
                  />
                </div>
                <div>
                  <label className="text-sm font-medium text-slate-200" htmlFor="signup-email">
                    Email
                  </label>
                  <input
                    id="signup-email"
                    type="email"
                    value={signUpForm.email}
                    onChange={(event) => setSignUpForm((prev) => ({ ...prev, email: event.target.value }))}
                    className="focus:border-brand-400 focus:ring-brand-400/60 mt-2 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="you@example.com"
                    autoComplete="email"
                  />
                </div>
                <div>
                  <label className="text-sm font-medium text-slate-200" htmlFor="signup-password">
                    Password
                  </label>
                  <input
                    id="signup-password"
                    type="password"
                    value={signUpForm.password}
                    onChange={(event) => setSignUpForm((prev) => ({ ...prev, password: event.target.value }))}
                    className="focus:border-brand-400 focus:ring-brand-400/60 mt-2 w-full rounded-xl border border-white/10 bg-slate-900/80 px-4 py-3 text-base text-slate-100 placeholder:text-slate-500 focus:ring-2 focus:outline-none"
                    placeholder="At least 6 characters"
                    autoComplete="new-password"
                  />
                </div>
                {errorMessage && (
                  <div className="rounded-xl border border-red-500/40 bg-red-500/10 px-4 py-3 text-sm text-red-200">{errorMessage}</div>
                )}
                <button
                  type="submit"
                  disabled={pending}
                  className="bg-brand-500 shadow-brand-500/40 hover:bg-brand-400 focus:ring-brand-400/50 disabled:bg-brand-500/60 w-full rounded-xl px-4 py-3 text-base font-semibold text-white shadow-lg transition focus:ring-4 focus:outline-none disabled:cursor-not-allowed"
                >
                  {pending ? 'Creating account...' : 'Create Account'}
                </button>
              </form>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

export default AuthScreen
