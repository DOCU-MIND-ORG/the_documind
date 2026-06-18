import React, { useEffect, useState } from 'react';
import { useToast } from '../context/ToastContext.jsx';

const OTP_EXPIRY_SECONDS = 10 * 60;

export default function ForgotPasswordModal({ isOpen, email, onClose }) {
  const endpoint = import.meta.env.VITE_API_URL;
  const { showToast } = useToast();
  const [step, setStep] = useState(1);
  const [form, setForm] = useState({
    otp: '',
    newPassword: '',
    confirmPassword: '',
  });
  const [loading, setLoading] = useState(false);
  const [otpExpiresAt, setOtpExpiresAt] = useState(null);
  const [otpTimeLeft, setOtpTimeLeft] = useState(0);
  const [verificationToken, setVerificationToken] = useState('');

  const handleChange = e => setForm(f => ({ ...f, [e.target.name]: e.target.value }));
  const isOtpActive = otpExpiresAt && otpTimeLeft > 0;
  const isOtpExpired = otpExpiresAt && otpTimeLeft <= 0;

  const formatTime = seconds => {
    const minutes = Math.floor(seconds / 60).toString().padStart(2, '0');
    const remainingSeconds = (seconds % 60).toString().padStart(2, '0');
    return `${minutes}:${remainingSeconds}`;
  };

  useEffect(() => {
    if (!isOpen) return;

    setStep(1);
    setForm({ otp: '', newPassword: '', confirmPassword: '' });
    setOtpExpiresAt(null);
    setOtpTimeLeft(0);
    setVerificationToken('');
  }, [isOpen, email]);

  useEffect(() => {
    if (!otpExpiresAt) return undefined;

    const updateTimeLeft = () => {
      setOtpTimeLeft(Math.max(0, Math.ceil((otpExpiresAt - Date.now()) / 1000)));
    };

    updateTimeLeft();
    const timerId = window.setInterval(updateTimeLeft, 1000);

    return () => window.clearInterval(timerId);
  }, [otpExpiresAt]);

  const readJson = async response => {
    try {
      return await response.json();
    } catch {
      return null;
    }
  };

  const handleRequestOTP = async e => {
    e.preventDefault();
    if (!email) {
      showToast('Please enter your email address on the login page first.', 'error');
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(`${endpoint}/auth/request-otp`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email }),
      });

      const data = await readJson(res);

      if (!res.ok) {
        showToast(data?.message || 'Failed to send OTP. Please try again.', 'error');
        return;
      }

      setForm(f => ({ ...f, otp: '' }));
      setVerificationToken('');
      setOtpExpiresAt(Date.now() + OTP_EXPIRY_SECONDS * 1000);
      showToast(data?.message || 'OTP sent to your email. It expires in 10 minutes.', 'success');
      setStep(2);
    } catch (err) {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOTP = async e => {
    e.preventDefault();
    if (!form.otp) {
      showToast('Please enter the OTP.', 'error');
      return;
    }
    if (!isOtpActive) {
      showToast('OTP expired. Please request a new OTP.', 'error');
      return;
    }

    setLoading(true);
    try {
      const res = await fetch(`${endpoint}/auth/verify-otp`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, otp: form.otp }),
      });

      const data = await readJson(res);

      if (!res.ok) {
        showToast(data?.message || 'Invalid OTP. Please try again.', 'error');
        return;
      }

      setVerificationToken(data?.verificationToken || '');
      showToast(data?.message || 'OTP verified successfully.', 'success');
      setStep(3);
    } catch (err) {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

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
          otp: form.otp,
          newPassword: form.newPassword,
          verificationToken,
        }),
      });

      const data = await readJson(res);

      if (!res.ok) {
        showToast(data?.message || 'Failed to reset password. Please try again.', 'error');
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
    setForm({ otp: '', newPassword: '', confirmPassword: '' });
    setOtpExpiresAt(null);
    setOtpTimeLeft(0);
    setVerificationToken('');
    onClose();
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center p-4 z-50">
      <div className="w-full max-w-[450px] bg-[#16181d]/95 backdrop-blur-xl border border-white/5 rounded-2xl px-6 py-8 sm:px-10 sm:py-12 shadow-2xl">
        <h2 className="text-2xl font-bold text-white mb-2 text-center">Reset Password</h2>
        <p className="text-center text-[#94a3b8] text-sm mb-6">
          {step === 1 && 'Send an OTP to your login email'}
          {step === 2 && 'Enter the OTP sent to your email'}
          {step === 3 && 'Create your new password'}
        </p>

        <form className="flex flex-col gap-5">
          {step === 1 ? (
            <>
              <div className="rounded-lg border border-white/10 bg-white/[0.03] px-4 py-3">
                <p className="text-xs text-[#94a3b8] mb-1">OTP will be sent to</p>
                <p className="text-sm text-white break-all">{email}</p>
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
          ) : step === 2 ? (
            <>
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between gap-3">
                  <label className="text-xs text-[#94a3b8] font-medium" htmlFor="otp">One-Time Password (OTP)</label>
                  {isOtpActive && (
                    <span className="text-xs text-emerald-300">Expires in {formatTime(otpTimeLeft)}</span>
                  )}
                  {isOtpExpired && (
                    <span className="text-xs text-red-300">OTP expired</span>
                  )}
                </div>
                <input
                  id="otp"
                  type="text"
                  name="otp"
                  inputMode="numeric"
                  className="bg-white/[0.03] border border-white/10 px-4 py-3 rounded-lg text-white text-sm transition-all focus:bg-white/[0.05] focus:border-white/20 focus:ring-4 focus:ring-white/[0.03] outline-none placeholder-white/20 disabled:opacity-70"
                  placeholder="Enter OTP"
                  value={form.otp}
                  onChange={handleChange}
                  disabled={isOtpExpired}
                  required
                />
                {isOtpExpired && (
                  <p className="text-xs text-red-300">OTP expired. Request a new OTP to continue.</p>
                )}
              </div>

              <button
                type="button"
                onClick={handleVerifyOTP}
                className="bg-white text-black py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-slate-100 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none flex items-center justify-center gap-2"
                disabled={loading || isOtpExpired}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-black/10 border-t-black rounded-full animate-spin" /> Verifying...</>
                ) : 'Verify OTP'}
              </button>

              <button
                type="button"
                onClick={handleRequestOTP}
                className="text-[#94a3b8] hover:text-white text-sm transition-colors disabled:opacity-70"
                disabled={loading}
              >
                Resend OTP
              </button>
            </>
          ) : (
            <>
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
                onClick={handleResetPassword}
                className="bg-white text-black py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-slate-100 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none flex items-center justify-center gap-2"
                disabled={loading}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-black/10 border-t-black rounded-full animate-spin" /> Resetting...</>
                ) : 'Reset Password'}
              </button>

              <button
                type="button"
                onClick={() => setStep(2)}
                className="text-[#94a3b8] hover:text-white text-sm transition-colors"
              >
                Back to OTP
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
