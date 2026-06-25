import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useToast } from '../context/ToastContext.jsx';

const OTP_EXPIRY_SECONDS = 10 * 60;

export default function ResetPassword() {
  const endpoint = import.meta.env.VITE_API_URL;
  const { showToast } = useToast();
  const navigate = useNavigate();
  const [step, setStep] = useState(1);
  const [email, setEmail] = useState('');
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
      showToast('Please enter your email address.', 'error');
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
      navigate('/login');
    } catch (err) {
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
          <h2 className="text-2xl font-bold text-slate-900 mb-2">Reset Password</h2>
          <p className="text-slate-500 text-sm">
            {step === 1 && 'Send an OTP to your login email'}
            {step === 2 && 'Enter the OTP sent to your email'}
            {step === 3 && 'Create your new password'}
          </p>
        </div>

        <form className="flex flex-col gap-5">
          {step === 1 ? (
            <>
              <div className="flex flex-col gap-2">
                <label className="text-xs text-slate-600 font-medium" htmlFor="email">Email Address</label>
                <input
                  id="email"
                  type="email"
                  name="email"
                  className="w-full bg-slate-50 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500"
                  placeholder="you@example.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                  required
                />
              </div>

              <button
                type="button"
                onClick={handleRequestOTP}
                className="bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-blue-500 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none mt-2 flex items-center justify-center gap-2"
                disabled={loading}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-white/20 border-t-white rounded-full animate-spin" /> Sending OTP...</>
                ) : 'Send OTP'}
              </button>
            </>
          ) : step === 2 ? (
            <>
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between gap-3">
                  <label className="text-xs text-slate-600 font-medium" htmlFor="otp">One-Time Password (OTP)</label>
                  {isOtpActive && (
                    <span className="text-xs text-emerald-600">Expires in {formatTime(otpTimeLeft)}</span>
                  )}
                  {isOtpExpired && (
                    <span className="text-xs text-red-500">OTP expired</span>
                  )}
                </div>
                <input
                  id="otp"
                  type="text"
                  name="otp"
                  inputMode="numeric"
                  className="w-full bg-slate-50 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500 disabled:opacity-70"
                  placeholder="Enter OTP"
                  value={form.otp}
                  onChange={handleChange}
                  disabled={isOtpExpired}
                  required
                />
                {isOtpExpired && (
                  <p className="text-xs text-red-500">OTP expired. Request a new OTP to continue.</p>
                )}
              </div>

              <button
                type="button"
                onClick={handleVerifyOTP}
                className="bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-blue-500 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none flex items-center justify-center gap-2"
                disabled={loading || isOtpExpired}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-white/20 border-t-white rounded-full animate-spin" /> Verifying...</>
                ) : 'Verify OTP'}
              </button>

              <button
                type="button"
                onClick={handleRequestOTP}
                className="text-slate-500 hover:text-blue-600 text-sm transition-colors disabled:opacity-70 mt-2"
                disabled={loading}
              >
                Resend OTP
              </button>
            </>
          ) : (
            <>
              <div className="flex flex-col gap-2">
                <label className="text-xs text-slate-600 font-medium" htmlFor="newPassword">New Password</label>
                <input
                  id="newPassword"
                  type="password"
                  name="newPassword"
                  className="w-full bg-slate-50 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500"
                  placeholder="Min 8 chars"
                  value={form.newPassword}
                  onChange={handleChange}
                  required
                />
              </div>

              <div className="flex flex-col gap-2">
                <label className="text-xs text-slate-600 font-medium" htmlFor="confirmPassword">Confirm Password</label>
                <input
                  id="confirmPassword"
                  type="password"
                  name="confirmPassword"
                  className="w-full bg-slate-50 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500"
                  placeholder="Re-enter password"
                  value={form.confirmPassword}
                  onChange={handleChange}
                  required
                />
              </div>

              <button
                type="button"
                onClick={handleResetPassword}
                className="bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-blue-500 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed flex items-center justify-center gap-2"
                disabled={loading}
              >
                {loading ? (
                  <><span className="w-4 h-4 border-2 border-white/20 border-t-white rounded-full animate-spin" /> Resetting...</>
                ) : 'Reset Password'}
              </button>

              <button
                type="button"
                onClick={() => setStep(2)}
                className="text-slate-500 hover:text-blue-600 text-sm transition-colors mt-2"
              >
                Back to OTP
              </button>
            </>
          )}
        </form>

        <div className="text-center mt-8 text-sm text-slate-600">
          Remember your password? <Link to="/login" className="text-blue-600 font-medium ml-1 hover:underline">Sign In</Link>
        </div>
      </div>
    </div>
  );
}
