import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import logo from '/light.png';

export default function Register() {
  const endpoint = import.meta.env.VITE_API_URL;
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
    const emailRegex = /^[^\s@]+@gmail\.com$/i;
    if (!emailRegex.test(form.email)) {
      showToast('Please enter a valid @gmail.com address.', 'error');
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
        body: JSON.stringify({ name: form.name, email: form.email, password: form.password }),
      });

      let data;
      try { data = await res.json(); } catch {
        showToast('Server returned an unexpected response. Please try again.', 'error');
        return;
      }

      if (!res.ok) {
        showToast(data.message || 'Registration failed. Please try again.', 'error');
        return;
      }

      if (data.user) login(data.user, data.accessToken);
      showToast(`Account created successfully! Welcome aboard, ${form.name}! 🎉`, 'success');
      navigate('/dashboard', { replace: true });
    } catch {
      showToast('Unable to reach the server. Please try again later.', 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 p-4 sm:p-8 relative overflow-hidden">
      <div className="pointer-events-none fixed inset-0 overflow-hidden">
        <div className="absolute top-[-20%] left-[-10%] w-[500px] h-[500px] rounded-full bg-blue-500/10 blur-[120px]" />
        <div className="absolute bottom-[-20%] right-[-10%] w-[400px] h-[400px] rounded-full bg-purple-500/10 blur-[120px]" />
      </div>

      <div className="relative w-full max-w-[450px] bg-white border border-slate-200 rounded-2xl flex flex-col px-6 py-8 sm:px-10 sm:py-12 shadow-xl animate-fade-in-up z-10">
        <div className="text-center mb-8">
  <div className="flex flex-col items-center mb-6">
    <div className="mb-3">
      <img
        src={logo}
        alt="DocuMind Logo"
        className="w-40 h-40 mx-auto object-contain"
      />
    </div>

    <h1 className="text-2xl font-bold text-slate-900">
      Dive into the Knowledge Constellation
    </h1>
  </div>

  <h2 className="text-xl text-slate-900 font-semibold mb-1">
    Create Account
  </h2>

  <p className="text-slate-500 text-sm">
    Join us to start querying
  </p>
</div>

        <form className="flex flex-col gap-5" onSubmit={handleSubmit}>
          <div className="flex flex-col gap-2">
            <label className="text-xs text-slate-500 font-medium" htmlFor="name">Full Name</label>
            <input
              id="name" type="text" name="name"
              className="bg-slate-100 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 transition-all"
              placeholder="Jane Doe"
              value={form.name} onChange={handleChange} required
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-xs text-slate-500 font-medium" htmlFor="email">Email Address</label>
            <input
              id="email" type="email" name="email"
              className="bg-slate-100 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 transition-all"
              placeholder="you@example.com"
              value={form.email} onChange={handleChange} required
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-xs text-slate-500 font-medium" htmlFor="password">Password</label>
              <input
                id="password" type="password" name="password"
                className="bg-slate-100 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 transition-all"
                placeholder="Min 8 chars"
                value={form.password} onChange={handleChange} required
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-xs text-slate-500 font-medium" htmlFor="confirm">Confirm</label>
              <input
                id="confirm" type="password" name="confirm"
                className="bg-slate-100 border border-slate-200 px-4 py-3 rounded-lg text-sm text-slate-900 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 transition-all"
                placeholder="Re-enter"
                value={form.confirm} onChange={handleChange} required
              />
            </div>
          </div>

          <button
            type="submit"
            className="bg-blue-600 text-white py-3 rounded-lg font-semibold text-sm cursor-pointer transition-all hover:bg-blue-500 hover:-translate-y-[1px] active:translate-y-0 disabled:opacity-70 disabled:cursor-not-allowed disabled:transform-none mt-2 flex items-center justify-center gap-2"
            disabled={loading}
          >
            {loading ? (
              <><span className="w-4 h-4 border-2 border-white/20 border-t-white rounded-full animate-spin" /> Creating account...</>
            ) : 'Create Account'}
          </button>
        </form>

        <div className="text-center mt-8 text-sm text-slate-500">
          Already have an account? <Link to="/login" className="text-blue-600 font-medium ml-1 hover:underline">Sign in</Link>
        </div>
      </div>
    </div>
  );
}
