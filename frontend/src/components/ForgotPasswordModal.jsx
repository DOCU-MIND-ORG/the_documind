import React, { useState } from 'react';
import { useToast } from '../context/ToastContext.jsx';

export default function ForgotPasswordModal({ isOpen, onClose }) {
  const endpoint = import.meta.env.VITE_API_URL;
  const { showToast } = useToast();
  const [step, setStep] = useState(1);
  const [form, setForm] = useState({
    email: '',
    otp: '',
    newPassword: '',
    confirmPassword: '',
  });
  const [loading, setLoading] = useState(false);

  const handleChange = e => setForm(f => ({ ...f, [e.target.name]: e.target.value }));

  const handleRequestOTP = async e => {
    e.preventDefault();
    if (!form.email) {
      showToast('Please enter your email address.', 'error');
      return;
    }
    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (!emailRegex.test(form.email)) {
      showToast('Please enter a valid email address.', 'error');
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(`${endpoint}/auth/request-otp`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: form.email }),
      });

      const data = await res.json();

      if (!res.ok) {
        showToast(data.message || 'Failed to send OTP. Please try again.', 'error');
        return;
      }

      showToast('OTP sent to your email. Please check your inbox.', 'success');
      setStep(2);
    } catch (err) {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOTPAndReset = async e => {
    e.preventDefault();
    if (!form.otp || !form.newPassword || !form.confirmPassword) {
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
        body: JSON.stringify({
          email: form.email,
          otp: form.otp,
          newPassword: form.newPassword,
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        showToast(data.message || 'Failed to reset password. Please try again.', 'error');
        return;
      }

      showToast('Password reset successfully! You can now log in.', 'success');
      handleClose();
    } catch (err) {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleClose = () => {
    setStep(1);
    setForm({ email: '', otp: '', newPassword: '', confirmPassword: '' });
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
      <div className="w-full max-w-[450px] bg-[#16181d]/95 backdrop-blur-xl border border-white/5 rounded-2xl px-6 py-8 sm:px-10 sm:py-12 shadow-2xl">
        <h2 className="text-2xl font-bold text-white mb-2 text-center">Reset Password</h2>
        <p className="text-center text-[#94a3b8] text-sm mb-6">
          {step === 1 ? 'Enter your email to receive an OTP' : 'Enter OTP and your new password'}
        </p>

        <form className="flex flex-col gap-5">
          {step === 1 ? (
            <>
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

              <button
                type="button"
                onClick={handleRequestOTP}
                className="bg-white text-black py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-slate-100 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none flex items-center justify-center gap-2"
                disabled={loading}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-black/10 border-t-black rounded-full animate-spin" /> Sending OTP...</>
                ) : 'Send OTP'}
              </button>
            </>
          ) : (
            <>
              <div className="flex flex-col gap-2">
                <label className="text-xs text-[#94a3b8] font-medium" htmlFor="otp">One-Time Password (OTP)</label>
                <input
                  id="otp"
                  type="text"
                  name="otp"
                  className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
                  placeholder="Enter OTP"
                  value={form.otp}
                  onChange={handleChange}
                  required
                />
              </div>

              <div className="flex flex-col gap-2">
                <label className="text-xs text-[#94a3b8] font-medium" htmlFor="newPassword">New Password</label>
                <input
                  id="newPassword"
                  type="password"
                  name="newPassword"
                  className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
                  placeholder="Min 8 chars"
                  value={form.newPassword}
                  onChange={handleChange}
                  required
                />
              </div>

              <div className="flex flex-col gap-2">
                <label className="text-xs text-[#94a3b8] font-medium" htmlFor="confirmPassword">Confirm Password</label>
                <input
                  id="confirmPassword"
                  type="password"
                  name="confirmPassword"
                  className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20"
                  placeholder="Re-enter password"
                  value={form.confirmPassword}
                  onChange={handleChange}
                  required
                />
              </div>

              <button
                type="button"
                onClick={handleVerifyOTPAndReset}
                className="bg-white text-black py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-slate-100 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none flex items-center justify-center gap-2"
                disabled={loading}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-black/10 border-t-black rounded-full animate-spin" /> Resetting...</>
                ) : 'Reset Password'}
              </button>

              <button
                type="button"
                onClick={() => setStep(1)}
                className="text-[#94a3b8] hover:text-white text-sm transition-colors"
              >
                Back to email
              </button>
            </>
          )}
        </form>

        <button
          onClick={handleClose}
          className="absolute top-4 right-4 text-[#94a3b8] hover:text-white text-2xl leading-none"
        >
          ×
        </button>
      </div>
    </div>
  );
}
