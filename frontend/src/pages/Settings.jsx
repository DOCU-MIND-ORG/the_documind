import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../services/api.js';
import { useTheme } from '../context/ThemeContext.jsx';

export default function Settings() {
  const { user, logout, updateUser } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('profile');
  const [collapsed, setCollapsed] = useState(false);
  const { theme, toggle, setTheme } = useTheme();

  // Profile form state
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [picture, setPicture] = useState('');
  const [gender, setGender] = useState('');
  const [occupation, setOccupation] = useState('');
  const [organization, setOrganization] = useState('');
  const [jobTitle, setJobTitle] = useState('');
  const [education, setEducation] = useState('');
  const [interests, setInterests] = useState('');
  const [industry, setIndustry] = useState('');
  const [bio, setBio] = useState('');
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);

  useEffect(() => {
    if (location.hash === '#preferences') {
      setActiveTab('preferences');
    } else {
      setActiveTab('profile');
    }
  }, [location]);

  useEffect(() => {
    if (user) {
      setName(user.name || '');
      setEmail(user.email || '');
      // guard against malformed phone storing email accidentally
      const phoneVal = user.phoneNumber || '';
      if (phoneVal && phoneVal.includes('@') && (!user.email || user.email === '')) {
        // if phone looks like an email and email missing, move it to email
        setEmail(phoneVal);
        setPhone('');
      } else if (phoneVal && phoneVal.includes('@')) {
        // phone contains an email — ignore it to avoid showing email in phone input
        setPhone('');
      } else {
        setPhone(phoneVal);
      }
      setPicture(user.profilePicture || '');
      setGender(user.gender || '');
      setOccupation(user.occupation || '');
      setOrganization(user.organization || '');
      setJobTitle(user.jobTitle || '');
      setEducation(user.education || '');
      setInterests(user.interests || '');
      setIndustry(user.industry || '');
      setBio(user.bio || '');
    }
  }, [user]);

  // Validation state
  const [errors, setErrors] = useState({});
  const [savedMessage, setSavedMessage] = useState('');
  const validate = () => {
    const e = {};
    if (!name || name.trim().length < 2) e.name = 'Enter your full name';
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) e.email = 'Invalid email';
    if (phone && phone.includes('@')) e.phone = 'Phone cannot contain an email address';
    else if (phone && !/^\+?[0-9\- ]{7,20}$/.test(phone)) e.phone = 'Invalid phone';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  useEffect(()=>{ validate(); }, [name, email, phone]);

  // Preferences state (persisted locally)
  const [model, setModel] = useState('Aizen Pro');
  const [responseStyle, setResponseStyle] = useState('Balanced');

  useEffect(() => {
    try {
      const prefs = JSON.parse(localStorage.getItem('prefs') || '{}');
      if (prefs.model) setModel(prefs.model);
      if (prefs.responseStyle) setResponseStyle(prefs.responseStyle);
    } catch {}
  }, []);

  const savePreferences = () => {
    const prefs = { model, responseStyle };
    try { localStorage.setItem('prefs', JSON.stringify(prefs)); } catch {}
    alert('Preferences saved');
  };

  const onPickPicture = async (file) => {
    if (!file) return;
    const reader = new FileReader();
    setIsUploading(true);
    reader.onload = () => {
      setPicture(reader.result);
      setIsUploading(false);
    };
    reader.onerror = () => setIsUploading(false);
    reader.readAsDataURL(file);
  };

  const saveProfile = async () => {
    if (!validate()) return;
    setIsSaving(true);
    try {
      // avoid clearing existing email on save; send fallback to current user.email
      const body = { name, email: (email || user?.email || ''), phoneNumber: phone, profilePicture: picture };
        // include extended profile fields
        body.gender = gender;
        body.occupation = occupation;
        body.organization = organization;
        body.jobTitle = jobTitle;
        body.education = education;
        body.interests = interests;
        body.industry = industry;
        body.bio = bio;
      // final sanity: block phone that contains '@'
      if (phone && phone.includes('@')) {
        setErrors(prev => ({ ...prev, phone: 'Phone cannot contain an email address' }));
        setIsSaving(false);
        return;
      }

      const res = await authApi.update(body);
      if (res?.user) {
        // Update auth context directly so localStorage persists the change
        try { updateUser(res.user); } catch {}
        // sync local form state with returned values to avoid desync
        setName(res.user.name || '');
        setEmail(res.user.email || '');
        setPhone(res.user.phoneNumber || '');
        setPicture(res.user.profilePicture || '');
        window.dispatchEvent(new CustomEvent('profile-updated', { detail: res.user }));
        // small inline confirmation
        setSavedMessage('Profile saved');
        setTimeout(() => setSavedMessage(''), 2500);
      }
    } catch (err) {
      setErrors(prev => ({ ...prev, form: err.message || 'Failed to update profile' }));
    } finally { setIsSaving(false); }
  };

  const deleteAccount = async () => {
    if (!confirm('Delete your account? This cannot be undone.')) return;
    try {
      await authApi.deleteMe();
      logout();
      navigate('/register');
    } catch (err) {
      alert(err.message || 'Failed to delete account');
    }
  };

  return (
    <div className="flex h-screen">
      {/* Settings Sidebar (collapsible) */}
      <aside className={`transition-all duration-300 ${collapsed ? 'w-20' : 'w-64'} panel panel-border p-4 flex flex-col gap-6`}> 
        <div className="flex items-center justify-between">
          <button onClick={() => navigate('/dashboard')} className="flex items-center gap-2 text-muted hover:text-main p-2 rounded">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12"></line>
              <polyline points="12 19 5 12 12 5"></polyline>
            </svg>
            {!collapsed && <span className="text-sm font-medium">Back</span>}
          </button>

          <div className="flex items-center gap-2">
            <button onClick={() => setCollapsed(c => !c)} className="p-2 rounded hover:bg-white/5">
              {collapsed ? (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M9 18l6-6-6-6"/></svg>
              ) : (
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M15 18l-6-6 6-6"/></svg>
              )}
            </button>
          </div>
        </div>

        <nav className="flex flex-col gap-2 mt-2">
          <button onClick={() => setActiveTab('profile')} className={`flex items-center gap-3 p-2 rounded ${activeTab==='profile' ? 'bg-white/10 text-main' : 'text-muted'}`}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="8" r="3"/><path d="M6 20c0-3.3 2.7-6 6-6s6 2.7 6 6"/></svg>
            {!collapsed && <span className="text-sm">Profile</span>}
          </button>
          <button onClick={() => setActiveTab('preferences')} className={`flex items-center gap-3 p-2 rounded ${activeTab==='preferences' ? 'bg-white/10 text-main' : 'text-muted'}`}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33"/></svg>
            {!collapsed && <span className="text-sm">Preferences</span>}
          </button>
        </nav>

        <div className="mt-auto flex flex-col gap-3">
          {!collapsed && <div className="text-xs text-muted">Tip: collapse the sidebar to save space.</div>}
        </div>
      </aside>

      {/* Settings Content */}
      <div className="flex-1 p-16 overflow-y-auto" style={{background: 'radial-gradient(circle at 50% 0%, rgba(59,130,246,0.02), transparent 50%)'}}>
        <div className="max-w-[800px] mx-auto mb-10">
          <h1 className="text-3xl font-bold text-main mb-2">{activeTab === 'profile' ? 'Profile' : 'Preferences'}</h1>
          <p className="text-muted text-sm sm:text-base">
            {activeTab === 'profile' 
              ? 'Manage your personal information and account security.' 
              : 'Customize your AI experience and interface.'}
          </p>
        </div>

          {activeTab === 'profile' && (
          <div className="max-w-[900px] mx-auto panel-card p-8 shadow-2xl animate-fade-in-up">
            <div className="flex gap-8 flex-col md:flex-row">
              <div className="w-full md:w-1/3 flex flex-col items-center gap-4">
                <div className="w-36 h-36 rounded-full overflow-hidden panel-muted border panel-border flex items-center justify-center">
                  {picture ? (
                    <img src={picture} alt="avatar" className="w-full h-full object-cover" />
                  ) : (
                    <div className="text-3xl font-bold text-main">{(user?.name||'T').charAt(0).toUpperCase()}</div>
                  )}
                </div>
                <label className="bg-white/5 px-4 py-2 rounded text-sm cursor-pointer">
                  Upload Photo
                  <input type="file" accept="image/*" className="hidden" onChange={(e) => onPickPicture(e.target.files?.[0])} />
                </label>
                <button className="text-sm text-red-400" onClick={() => setPicture('')}>Remove Photo</button>
              </div>

                <div className="flex-1">
                <h3 className="text-lg font-semibold text-main mb-4">Personal Information</h3>
                <div className="flex flex-col gap-4">
                  <div className="flex flex-col gap-2">
                    <label className="text-xs text-muted font-medium">Full Name</label>
                    <input value={name} onChange={(e) => setName(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    {errors.name && <div className="text-sm text-red-400 mt-1">{errors.name}</div>}
                  </div>
                  <div className="flex flex-col gap-2">
                    <label className="text-xs text-muted font-medium">Email Address</label>
                    <input value={email} onChange={(e) => setEmail(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    {errors.email && <div className="text-sm text-red-400 mt-1">{errors.email}</div>}
                  </div>
                  <div className="flex flex-col gap-2">
                    <label className="text-xs text-muted font-medium">Phone Number</label>
                    <input value={phone} onChange={(e) => setPhone(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    {errors.phone && <div className="text-sm text-red-400 mt-1">{errors.phone}</div>}
                  </div>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div className="flex flex-col gap-2">
                      <label className="text-xs text-muted font-medium">Gender</label>
                      <select value={gender} onChange={(e)=>setGender(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none">
                        <option value="">Prefer not to say</option>
                        <option>Female</option>
                        <option>Male</option>
                        <option>Non-binary</option>
                        <option>Other</option>
                      </select>
                    </div>

                    <div className="flex flex-col gap-2">
                      <label className="text-xs text-muted font-medium">Occupation</label>
                      <input value={occupation} onChange={(e)=>setOccupation(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    </div>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mt-4">
                    <div className="flex flex-col gap-2">
                      <label className="text-xs text-muted font-medium">Organization</label>
                      <input value={organization} onChange={(e)=>setOrganization(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    </div>

                    <div className="flex flex-col gap-2">
                      <label className="text-xs text-muted font-medium">Job Title</label>
                      <input value={jobTitle} onChange={(e)=>setJobTitle(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    </div>
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mt-4">
                    <div className="flex flex-col gap-2">
                      <label className="text-xs text-muted font-medium">Education</label>
                      <input value={education} onChange={(e)=>setEducation(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    </div>

                    <div className="flex flex-col gap-2">
                      <label className="text-xs text-muted font-medium">Industry</label>
                      <input value={industry} onChange={(e)=>setIndustry(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                    </div>
                  </div>

                  <div className="flex flex-col gap-2 mt-4">
                    <label className="text-xs text-muted font-medium">Interests (comma separated)</label>
                    <input value={interests} onChange={(e)=>setInterests(e.target.value)} className="input-bg px-4 py-3 rounded-lg outline-none" />
                  </div>

                  <div className="flex flex-col gap-2 mt-4">
                    <label className="text-xs text-muted font-medium">Short Bio</label>
                    <textarea value={bio} onChange={(e)=>setBio(e.target.value)} rows={4} className="input-bg px-4 py-3 rounded-lg outline-none" />
                  </div>
                  <div className="flex flex-col gap-2">
                    <label className="text-xs text-muted font-medium">Password</label>
                    <input type="password" placeholder="Leave blank to keep current" className="input-bg px-4 py-3 rounded-lg outline-none" onChange={() => {}} />
                  </div>
                </div>

                <div className="mt-6 flex gap-3">
                  <button onClick={saveProfile} disabled={isSaving || isUploading || Object.keys(errors).length>0} className="bg-white text-black px-6 py-2 rounded font-semibold">{isSaving ? 'Saving...' : 'Save'}</button>
                  <button onClick={logout} className="bg-white/5 text-white px-6 py-2 rounded border border-white/10">Sign Out</button>
                  <button onClick={deleteAccount} className="ml-auto bg-red-500/10 text-red-500 px-4 py-2 rounded border border-red-500/20">Delete Account</button>
                </div>
                {errors.form && <div className="text-sm text-red-400 mt-3">{errors.form}</div>}
                {savedMessage && <div className="text-sm text-green-400 mt-3">{savedMessage}</div>}
              </div>
            </div>
          </div>
        )}

          {activeTab === 'preferences' && (
          <div className="max-w-[800px] mx-auto panel-card p-10 shadow-2xl animate-fade-in-up">
              <div className="settings-section">
              <h3 className="text-lg font-semibold text-white mb-6">AI Configuration</h3>
              <div className="flex flex-col gap-2 mb-6 max-w-[400px]">
                <label className="text-xs text-muted font-medium">Default AI Model</label>
                <select value={model} onChange={(e)=>setModel(e.target.value)} className="app-select text-sm">
                  <option>Aizen Pro</option>
                  <option>Aizen Lite</option>
                  <option>Claude 3.5 Sonnet</option>
                </select>
                <span className="text-[11px] text-muted mt-1">The model used for new sessions.</span>
              </div>
              
              <div className="flex flex-col gap-2 mb-6 max-w-[400px]">
                <label className="text-xs text-muted font-medium">Response Style</label>
                <select value={responseStyle} onChange={(e)=>setResponseStyle(e.target.value)} className="app-select text-sm">
                  <option>Balanced</option>
                  <option>Concise</option>
                  <option>Detailed</option>
                </select>
                <span className="text-[11px] text-muted mt-1">How verbose the AI should be.</span>
              </div>
            </div>

            <div className="h-px bg-white/5 my-10"></div>

            <div className="settings-section">
              <h3 className="text-lg font-semibold text-white mb-6">Interface</h3>
              <div className="flex flex-col gap-2 mb-6 max-w-[400px]">
                <label className="text-xs text-muted font-medium">Theme</label>
                <div className="flex items-center gap-3">
                  <label className="relative inline-flex items-center cursor-pointer">
                    <input type="checkbox" checked={theme === 'dark'} onChange={() => toggle()} className="sr-only" />
                    <div className="w-14 h-8 bg-white/10 rounded-full shadow-inner flex items-center p-1 transition-colors" style={{background: theme==='dark' ? '#334155' : '#e2e8f0'}}>
                      <div className="w-6 h-6 bg-white rounded-full transition-transform" style={{transform: theme==='dark' ? 'translateX(28px)' : 'translateX(0)'}} />
                    </div>
                    <span className="ml-3 text-sm text-muted">{theme === 'dark' ? 'Dark' : 'Light'}</span>
                  </label>
                </div>
              </div>
            </div>
            
            <div className="mt-8 flex justify-end">
              <button onClick={savePreferences} className="bg-white text-black px-8 py-3 rounded-lg font-semibold transition-colors hover:bg-slate-100 cursor-pointer">Save Preferences</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
