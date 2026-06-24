import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useToast } from '../context/ToastContext.jsx';
import logo from "../assets/Logo.png";

export default function ResetPassword() {
  const endpoint = import.meta.env.VITE_API_URL;
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [searchParams] = useSearchParams();

  const email = searchParams.get('email');
  const otp = searchParams.get('otp');
  const verificationToken = searchParams.get('token');

  const [form, setForm] = useState({
    newPassword: '',
    confirmPassword: '',
  });
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!email || !otp || !verificationToken) {
      showToast('Invalid reset link. Please request a password reset again.', 'error');
      navigate('/login', { replace: true });
    }
  }, [email, otp, verificationToken, navigate, showToast]);

  const handleChange = e => setForm(f => ({ ...f, [e.target.name]: e.target.value }));

  const handleResetPassword = async e => {
    e.preventDefault();
    if (!form.newPassword || !form.confirmPassword) {
      showToast('Please fill in all fields.', 'error');
      return;
    }
    if (form.newPassword !== form.confirmPassword) {
      showToast('Passwords do not match.', 'error');
      return;
    }
    if (form.newPassword.length < 8) {
      showToast('Password must be at least 8 characters.', 'error');
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(`${endpoint}/auth/reset-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          email,
          otp,
          newPassword: form.newPassword,
          verificationToken,
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        showToast(data?.message || 'Failed to reset password. Please try again.', 'error');
        return;
      }

      showToast('Password reset successfully! You can now log in.', 'success');
      navigate('/login', { replace: true });
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
            Reset Your Password
          </h2>

          <p className="text-[#94a3b8] text-sm">
            Create a new password to secure your account
          </p>
        </div>

        <form className="flex flex-col gap-5" onSubmit={handleResetPassword}>
          <div className="flex flex-col gap-2">
            <label className="text-xs t-text-muted font-medium" htmlFor="newPassword">New Password</label>
            <input
              id="newPassword"
              type="password"
              name="newPassword"
              className="input-bg px-4 py-3 rounded-lg text-sm outline-none"
              placeholder="Min 8 characters"
              value={form.newPassword}
              onChange={handleChange}
              autoComplete="new-password"
              required
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs t-text-muted font-medium" htmlFor="confirmPassword">Confirm Password</label>
            <input
              id="confirmPassword"
              type="password"
              name="confirmPassword"
              className="input-bg px-4 py-3 rounded-lg text-sm outline-none"
              placeholder="Re-enter password"
              value={form.confirmPassword}
              onChange={handleChange}
              autoComplete="new-password"
              required
            />
          </div>

          <button
            type="submit"
            className="bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-blue-500 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none mt-2 flex items-center justify-center gap-2"
            disabled={loading}
          >
            {loading ? (
              <><span className="w-4 h-4 border-2 border-white/20 border-t-white rounded-full animate-spin" /> Resetting...</>
            ) : 'Reset Password'}
          </button>
        </form>

        <div className="text-center mt-6 text-sm t-text-muted">
          Remember your password? <button onClick={() => navigate('/login')} className="text-blue-500 font-medium ml-1 hover:underline bg-none border-none cursor-pointer">Sign in</button>
        </div>
      </div>
    </div>
  );
}
