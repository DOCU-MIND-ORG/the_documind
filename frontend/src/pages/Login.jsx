import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';

export default function Login() {
  const endpoint = import.meta.env.VITE_API_URL;
  const navigate = useNavigate();
  const { login } = useAuth();
  const { showToast } = useToast();
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);

  const handleChange = e => setForm(f => ({ ...f, [e.target.name]: e.target.value }));
  const isValidEmail = email => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);

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
    <div className="min-h-screen flex items-center justify-center bg-slate-50 p-4 sm:p-8">
      <div className="w-full max-w-[450px] bg-white rounded-2xl flex flex-col px-6 py-8 sm:px-10 sm:py-12 shadow-2xl animate-fade-in-up">
        <div className="text-center mb-8">
          <div className="flex flex-col items-center mb-6">
            <div className="mb-2">
              <img
                src="/logodoc.png"
                alt="DocuMind Logo"
                className="w-56 h-auto mx-auto object-contain"
              />
            </div>
          </div>

          <h2 className="text-xl text-slate-900 font-semibold mb-1">
            Welcome back
          </h2>

          <p className="text-slate-500 text-sm">
            Sign in to continue your session
          </p>
        </div>
        <form className="flex flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className="text-xs text-slate-600 font-medium" htmlFor="email">Email Address</label>
            <input
              id="email"
              type="email"
              name="email"
              className="w-full bg-slate-50 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500"
              placeholder="you@example.com"
              value={form.email}
              onChange={handleChange}
              autoComplete="email"
              required
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs text-slate-600 font-medium" htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              name="password"
              className="w-full bg-slate-50 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500"
              placeholder="••••••••"
              value={form.password}
              onChange={handleChange}
              autoComplete="current-password"
              required
            />
          </div>

          <div className="flex justify-between items-center text-xs mt-[-4px]">
            <label className="flex items-center gap-2 text-slate-600 cursor-pointer">
              <input type="checkbox" className="cursor-pointer accent-blue-500" />
              <span>Remember me</span>
            </label>
            <Link
              to="/reset-password"
              className="text-slate-500 transition-colors hover:text-blue-600"
            >
              Forgot password?
            </Link>
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

        <div className="text-center mt-8 text-sm text-slate-600">
          Don't have an account? <Link to="/register" className="text-blue-600 font-medium ml-1 hover:underline">Create one</Link>
        </div>
      </div>
    </div>
  );
}
