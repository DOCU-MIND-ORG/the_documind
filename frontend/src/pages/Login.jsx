import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import ForgotPasswordModal from '../components/ForgotPasswordModal.jsx';
import logo from "../assets/Logo.png";

export default function Login() {
  const endpoint = import.meta.env.VITE_API_URL;
  const navigate = useNavigate();
  const { login } = useAuth();
  const { showToast } = useToast();
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [showForgotPassword, setShowForgotPassword] = useState(false);

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
    } catch (err) {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

  

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#0f1115] bg-[radial-gradient(circle_at_15%_50%,rgba(59,130,246,0.12),transparent_25%),radial-gradient(circle_at_85%_30%,rgba(147,51,234,0.12),transparent_25%)] p-4 sm:p-8">
      <div className="w-full max-w-[450px] bg-[#16181d]/60 backdrop-blur-xl border border-white/5 rounded-2xl flex flex-col px-6 py-8 sm:px-10 sm:py-12 shadow-2xl animate-fade-in-up">
       <div className="text-center mb-8">
  <div className="flex flex-col items-center mb-6">
   <div className="mb-3">
  <img
    src={logo}
    alt="DocuMind Logo"
    className="w-32 h-32 mx-auto object-contain"
  />
</div>

    <h1 className="text-4xl font-bold bg-gradient-to-r from-white to-slate-300 bg-clip-text text-transparent">
      DocuMind
    </h1>
  </div>

  <h2 className="text-xl text-white font-semibold mb-1">
    Welcome back
  </h2>

  <p className="text-[#94a3b8] text-sm">
    Sign in to continue your session
  </p>
</div>
        <form className="flex flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className="text-xs text-[#94a3b8] font-medium" htmlFor="email">Email Address</label>
            <input
              id="email"
              type="email"
              name="email"
              className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
              placeholder="you@example.com"
              value={form.email}
              onChange={handleChange}
              autoComplete="email"
              required
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs text-[#94a3b8] font-medium" htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              name="password"
              className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
              placeholder="••••••••"
              value={form.password}
              onChange={handleChange}
              autoComplete="current-password"
              required
            />
          </div>

          <div className="flex justify-between items-center text-xs mt-[-4px]">
            <label className="flex items-center gap-2 text-[#94a3b8] cursor-pointer">
              <input type="checkbox" className="accent-[#e2e8f0] cursor-pointer" />
              <span>Remember me</span>
            </label>
            <button
              type="button"
              onClick={() => setShowForgotPassword(true)}
              className="text-[#94a3b8] transition-colors hover:text-white"
            >
              Forgot password?
            </button>
          </div>

          <button
            type="submit"
            className="bg-white text-black py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-slate-100 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none mt-2 flex items-center justify-center gap-2"
            disabled={loading}
          >
            {loading ? (
              <><span className="w-4 h-4 border-2 border-black/10 border-t-black rounded-full animate-spin" /> Signing in...</>
            ) : 'Sign In'}
          </button>
        </form>

        <div className="text-center mt-8 text-sm text-[#94a3b8]">
          Don't have an account? <Link to="/register" className="text-white font-medium ml-1 hover:underline">Create one</Link>
        </div>
      </div>

      <ForgotPasswordModal
        isOpen={showForgotPassword}
        onClose={() => setShowForgotPassword(false)}
      />
    </div>
  );
}
