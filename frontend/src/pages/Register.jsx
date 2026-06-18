import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';


export default function Register() {
  const endpoint = import.meta.env.VITE_API_URL || '';
  const navigate = useNavigate();
  const { login } = useAuth();
  const { showToast } = useToast();
  const [form, setForm] = useState({ name: '', email: '', password: '', confirm: '' });
  const [loading, setLoading] = useState(false);

  const handleChange = e => setForm(f => ({ ...f, [e.target.name]: e.target.value }));

  const handleSubmit = async e => {
    e.preventDefault();
    if (!form.name || !form.email || !form.password) {
      showToast('Please fill in all required fields.', 'error');
      return;
    }
    if (form.password !== form.confirm) {
      showToast('Passwords do not match.', 'error');
      return;
    }
    if (form.password.length < 8) {
      showToast('Password must be at least 8 characters.', 'error');
      return;
    }
    setLoading(true);
    try {
      const res = await fetch(`${endpoint}/auth/signup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          name: form.name,
          email: form.email,
          password: form.password,
        }),
      });

      let data;
      try {
        data = await res.json();
      } catch {
        showToast('Server returned an unexpected response. Please try again.', 'error');
        return;
      }

      if (!res.ok) {
        showToast(data.message || 'Registration failed. Please try again.', 'error');
        return;
      }

      if (data.user) {
        login(data.user, data.accessToken);
      }
      showToast(`Account created successfully! Welcome aboard, ${form.name}! 🎉`, 'success');
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
          <div className="text-3xl mb-2 inline-block bg-gradient-to-r from-white to-[#94a3b8] bg-clip-text text-transparent font-bold tracking-tight">DocuMind</div>
          <h1 className="text-xl text-white font-semibold mb-1">Create Account</h1>
          <p className="text-[#94a3b8] text-sm">Join us to start querying</p>
        </div>

        <form className="flex flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className="text-xs text-[#94a3b8] font-medium" htmlFor="name">Full Name</label>
            <input
              id="name"
              type="text"
              name="name"
              className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
              placeholder="Jane Doe"
              value={form.name}
              onChange={handleChange}
              required
            />
          </div>

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
              required
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-xs text-[#94a3b8] font-medium" htmlFor="password">Password</label>
              <input
                id="password"
                type="password"
                name="password"
                className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
                placeholder="Min 8 chars"
                value={form.password}
                onChange={handleChange}
                required
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs text-[#94a3b8] font-medium" htmlFor="confirm">Confirm</label>
              <input
                id="confirm"
                type="password"
                name="confirm"
                className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
                placeholder="Re-enter"
                value={form.confirm}
                onChange={handleChange}
                required
              />
            </div>
          </div>

          <button
            type="submit"
            className="bg-white text-black py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-slate-100 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none mt-2 flex items-center justify-center gap-2"
            disabled={loading}
          >
            {loading ? (
              <><span className="w-4 h-4 border-2 border-black/10 border-t-black rounded-full animate-spin" /> Creating account...</>
            ) : 'Create Account'}
          </button>
        </form>

        <div className="text-center mt-8 text-sm text-[#94a3b8]">
          Already have an account? <Link to="/login" className="text-white font-medium ml-1 hover:underline">Sign in</Link>
        </div>
      </div>
    </div>
  );
}
