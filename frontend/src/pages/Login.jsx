import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';

export default function Login() {
  const endpoint = import.meta.env.VITE_API_URL || '';
  const navigate = useNavigate();
  const { login } = useAuth();
  const { showToast } = useToast();
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);

  const handleChange = e => setForm(f => ({ ...f, [e.target.name]: e.target.value }));

  const handleSubmit = async e => {
    e.preventDefault();
    if (!form.email || !form.password) {
      showToast('Please fill in all fields.', 'error');
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${endpoint}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email: form.email, password: form.password }),
      });

      const data = await res.json();

      if (!res.ok) {
        showToast(data.message || 'Login failed. Please check your credentials.', 'error');
        return;
      }

      login(data.user, data.accessToken);
      showToast(`Welcome back, ${data.user?.name || data.user?.email}! 🎉`, 'success');
      navigate('/dashboard', { replace: true });
    } catch {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center t-bg-main p-4 sm:p-8"
      style={{ background: 'var(--bg-main)' }}>
      {/* Subtle gradient orbs */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-[-20%] left-[-10%] w-[500px] h-[500px] rounded-full bg-blue-500/10 blur-[120px]" />
        <div className="absolute bottom-[-20%] right-[-10%] w-[400px] h-[400px] rounded-full bg-purple-500/10 blur-[120px]" />
      </div>

      <div className="relative w-full max-w-[420px] panel-card flex flex-col px-6 py-8 sm:px-10 sm:py-12 animate-fade-in-up">
        {/* Logo */}
        <div className="text-center mb-8">
          <div className="text-3xl mb-2 font-bold tracking-tight t-text-main">DocuMind</div>
          <h1 className="text-xl font-semibold t-text-main mb-1">Welcome back</h1>
          <p className="t-text-muted text-sm">Sign in to continue your session</p>
        </div>

        <form className="flex flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className="text-xs t-text-muted font-medium" htmlFor="email">Email Address</label>
            <input
              id="email"
              type="email"
              name="email"
              className="input-bg px-4 py-3 rounded-lg text-sm outline-none"
              placeholder="you@example.com"
              value={form.email}
              onChange={handleChange}
              autoComplete="email"
              required
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs t-text-muted font-medium" htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              name="password"
              className="input-bg px-4 py-3 rounded-lg text-sm outline-none"
              placeholder="••••••••"
              value={form.password}
              onChange={handleChange}
              autoComplete="current-password"
              required
            />
          </div>

          <div className="flex justify-between items-center text-xs mt-[-4px]">
            <label className="flex items-center gap-2 t-text-muted cursor-pointer">
              <input type="checkbox" className="cursor-pointer accent-blue-500" />
              <span>Remember me</span>
            </label>
            <a href="#" className="t-text-muted transition-colors hover:text-blue-500">Forgot password?</a>
          </div>

          <button
            type="submit"
            className="bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-blue-500 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none mt-2 flex items-center justify-center gap-2"
            disabled={loading}
          >
            {loading ? (
              <><span className="w-4 h-4 border-2 border-white/20 border-t-white rounded-full animate-spin" /> Signing in...</>
            ) : 'Sign In'}
          </button>
        </form>

        <div className="text-center mt-8 text-sm t-text-muted">
          Don't have an account? <Link to="/register" className="text-blue-500 font-medium ml-1 hover:underline">Create one</Link>
        </div>
      </div>
    </div>
  );
}
