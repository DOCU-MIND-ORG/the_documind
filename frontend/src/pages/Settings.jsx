import { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTheme } from '../context/ThemeContext.jsx';
import { authService } from '../services/authService.js';
import { preferenceService } from '../services/preferenceService.js';

function Field({ label, hint, error, children }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-xs font-semibold uppercase tracking-wider" style={{ color: 'var(--color-text-tertiary)' }}>
        {label}
      </label>
      {children}
      {hint && !error && <span className="text-[11px]" style={{ color: 'var(--color-text-tertiary)' }}>{hint}</span>}
      {error && <span className="text-[11px] text-red-500">{error}</span>}
    </div>
  );
}

function Section({ title, children }) {
  return (
    <div className="py-8" style={{ borderBottom: '1px solid var(--color-border)' }}>
      <h3 className="text-sm font-semibold mb-5" style={{ color: 'var(--color-text-secondary)' }}>{title}</h3>
      <div className="flex flex-col gap-5">{children}</div>
    </div>
  );
}

export default function Settings() {
  const { user, logout, updateUser } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('profile');
  const { theme, toggle } = useTheme();

  const [name,         setName]         = useState('');
  const [email,        setEmail]        = useState('');
  const [phone,        setPhone]        = useState('');
  const [picture,      setPicture]      = useState('');
  const [gender,       setGender]       = useState('');
  const [occupation,   setOccupation]   = useState('');
  const [organization, setOrganization] = useState('');
  const [jobTitle,     setJobTitle]     = useState('');
  const [education,    setEducation]    = useState('');
  const [interests,    setInterests]    = useState('');
  const [industry,     setIndustry]     = useState('');
  const [bio,          setBio]          = useState('');
  const [isSaving,     setIsSaving]     = useState(false);
  const [isUploading,  setIsUploading]  = useState(false);
  const [errors,       setErrors]       = useState({});
  const [savedMsg,     setSavedMsg]     = useState('');

  const [model,         setModel]         = useState('GEMINI_2_5_FLASH');
  const [responseStyle, setResponseStyle] = useState('BALANCED');
  const [prefSaved,     setPrefSaved]     = useState(false);
  const [availableModels, setAvailableModels] = useState([]);

  useEffect(() => {
    setActiveTab(location.hash === '#preferences' ? 'preferences' : 'profile');
  }, [location]);

  useEffect(() => {
    if (!user) return;
    setName(user.name || '');
    setEmail(user.email || '');
    const p = user.phoneNumber || '';
    setPhone(p && p.includes('@') ? '' : p);
    setPicture(user.profileImageUrl || '');
    setGender(user.gender || '');
    setOccupation(user.occupation || '');
    setOrganization(user.organization || '');
    setJobTitle(user.jobTitle || '');
    setEducation(user.education || '');
    setInterests(user.interests || '');
    setIndustry(user.industry || '');
    setBio(user.bio || '');
  }, [user]);

  useEffect(() => {
    if (!user) return;
    const fetchPrefs = async () => {
      try {
        const [prefsRes, modelsRes] = await Promise.all([
          preferenceService.get(),
          preferenceService.getModels()
        ]);
        if (modelsRes) setAvailableModels(modelsRes);
        if (prefsRes) {
          if (prefsRes.modelName) setModel(prefsRes.modelName);
          if (prefsRes.responseStyle) setResponseStyle(prefsRes.responseStyle);
          if (prefsRes.theme && prefsRes.theme !== theme) {
            // If different theme then toggle the current one
          }
        }
      } catch (err) {
        console.error("Failed to load preferences", err);
      }
    };
    fetchPrefs();
  }, [user]);

  const validate = () => {
    const e = {};
    if (!name || name.trim().length < 2) e.name = 'Enter your full name (at least 2 characters)';
    if (!email || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) e.email = 'Enter a valid email address';
    if (phone && phone.includes('@')) e.phone = 'Phone number cannot contain an email address';
    else if (phone && !/^\+?[0-9\- ]{7,20}$/.test(phone)) e.phone = 'Enter a valid phone number';
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  useEffect(() => { validate(); }, [name, email, phone]);

  const onPickPicture = async (file) => {
    if (!file) return;
    setIsUploading(true);
    try {
      const formData = new FormData();
      formData.append("file", file);
      formData.append("upload_preset", "Documind");
      formData.append("quality", "100");

      const response = await fetch(
        "https://api.cloudinary.com/v1_1/dinp3cp9p/image/upload",
        {
          method: "POST",
          body: formData,
        }
      );

      const result = await response.json();

      if (!result.secure_url || !result.public_id) {
        throw new Error("Upload to Cloudinary failed");
      }

      const res = await authService.updateProfileImage({
        link: result.secure_url,
        public_id: result.public_id,
      });

      if (res?.user) {
        try { updateUser(res.user); } catch (_) { /* ignore */ }
        setPicture(res.user.profileImageUrl || '');
        window.dispatchEvent(new CustomEvent('profile-updated', { detail: res.user }));
        setSavedMsg('Profile photo updated');
        setTimeout(() => setSavedMsg(''), 3000);
      }
    } catch (err) {
      console.error(err);
      setErrors(prev => ({ ...prev, form: 'Failed to upload photo' }));
    } finally {
      setIsUploading(false);
    }
  };

  const saveProfile = async () => {
    if (!validate()) return;
    if (phone && phone.includes('@')) return;
    setIsSaving(true);
    try {
      const body = {
        name, email: email || user?.email || '',
        phoneNumber: phone,
        gender, occupation, organization, jobTitle,
        education, interests, industry, bio,
      };
      const res = await authService.update(body);
      if (res?.user) {
        try { updateUser(res.user); } catch (_) { /* ignore */ }
        setName(res.user.name || '');
        setEmail(res.user.email || '');
        setPhone(res.user.phoneNumber || '');
        setPicture(res.user.profileImageUrl || '');
        window.dispatchEvent(new CustomEvent('profile-updated', { detail: res.user }));
        setSavedMsg('Changes saved');
        setTimeout(() => setSavedMsg(''), 3000);
      }
    } catch (err) {
      setErrors(prev => ({ ...prev, form: err.message || 'Failed to update profile' }));
    } finally {
      setIsSaving(false);
    }
  };

  const deleteAccount = async () => {
    if (!confirm('Delete your account? This cannot be undone.')) return;
    try { await authService.deleteMe(); logout(); navigate('/register'); }
    catch (err) { alert(err.message || 'Failed to delete account'); }
  };

  const savePreferences = async () => {
    try {
      await preferenceService.updateModel({
        modelName: model,
        responseStyle: responseStyle,
        theme: theme
      });
      setPrefSaved(true);
      setTimeout(() => setPrefSaved(false), 3000);
    } catch (err) {
      console.error("Failed to save preferences", err);
      alert("Failed to save preferences");
    }
  };

  const hasErrors = Object.keys(errors).length > 0;

  const inputStyle = {
    backgroundColor: 'var(--color-bg-input)',
    color:           'var(--color-text-primary)',
    border:          '1px solid var(--color-border)',
    borderRadius:    '0.5rem',
    padding:         '0.625rem 0.875rem',
    fontSize:        '0.875rem',
    outline:         'none',
    width:           '100%',
    transition:      'border-color 0.15s',
  };

  const tabs = [
    { id: 'profile',     label: 'Profile' },
    { id: 'preferences', label: 'Preferences' },
  ];

  return (
    <div className="flex-1 h-full overflow-y-auto" style={{ backgroundColor: 'var(--color-bg-base)' }}>
      <div
        className="sticky top-0 z-10"
        style={{ backgroundColor: 'var(--color-bg-surface)', borderBottom: '1px solid var(--color-border)' }}
      >
        <div className="max-w-2xl mx-auto px-6 flex items-center gap-4 h-14">
          <button
            onClick={() => navigate('/dashboard')}
            className="flex items-center gap-1.5 text-sm font-medium mr-2 transition-colors"
            style={{ color: 'var(--color-text-secondary)' }}
            onMouseEnter={e => e.currentTarget.style.color = 'var(--color-text-primary)'}
            onMouseLeave={e => e.currentTarget.style.color = 'var(--color-text-secondary)'}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="15 18 9 12 15 6" />
            </svg>
            Back
          </button>
          <div className="flex items-center gap-1">
            {tabs.map(t => (
              <button
                key={t.id}
                onClick={() => setActiveTab(t.id)}
                className="px-3 py-1.5 rounded-lg text-sm font-medium transition-all"
                style={{
                  backgroundColor: activeTab === t.id ? 'var(--color-bg-active)' : 'transparent',
                  color:           activeTab === t.id ? 'var(--color-text-primary)' : 'var(--color-text-secondary)',
                }}
              >
                {t.label}
              </button>
            ))}
          </div>
        </div>
      </div>
      <div className="max-w-2xl mx-auto px-6 pb-20">
        {activeTab === 'profile' && (
          <div className="animate-fade-in-up">
            <div className="pt-10 pb-6">
              <h1 className="text-2xl font-bold" style={{ color: 'var(--color-text-primary)' }}>Profile</h1>
              <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                Manage your personal information and how others see you.
              </p>
            </div>
            <Section title="Photo">
              <div className="flex items-center gap-5">
                <div
                  className="w-20 h-20 rounded-full overflow-hidden flex items-center justify-center shrink-0 text-2xl font-bold"
                  style={{ backgroundColor: 'var(--color-bg-active)', color: 'var(--color-text-primary)', border: '2px solid var(--color-border)' }}
                >
                  {picture
                    ? <img src={picture} alt="avatar" className="w-full h-full object-cover" />
                    : (user?.name || 'U').charAt(0).toUpperCase()
                  }
                </div>

                <div className="flex flex-col gap-2">
                  <label
                    className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium cursor-pointer transition-colors"
                    style={{
                      backgroundColor: 'var(--color-bg-active)',
                      color:           'var(--color-text-primary)',
                      border:          '1px solid var(--color-border)',
                    }}
                  >
                    {isUploading ? 'Uploading…' : 'Upload photo'}
                    <input type="file" accept="image/*" className="hidden" onChange={e => onPickPicture(e.target.files?.[0])} />
                  </label>
                  {picture && (
                    <button
                      onClick={() => setPicture('')}
                      className="text-sm text-red-500 hover:text-red-400 text-left transition-colors"
                    >
                      Remove photo
                    </button>
                  )}
                </div>
              </div>
            </Section>
            <Section title="Basic information">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
                <Field label="Full Name" error={errors.name}>
                  <input
                    value={name}
                    onChange={e => setName(e.target.value)}
                    placeholder="Jane Doe"
                    style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}
                  />
                </Field>
                <Field label="Email Address" error={errors.email}>
                  <input
                    type="email"
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    placeholder="you@example.com"
                    style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}
                  />
                </Field>
                <Field label="Phone Number" error={errors.phone}>
                  <input
                    type="tel"
                    value={phone}
                    onChange={e => setPhone(e.target.value)}
                    placeholder="+91 98765 43210"
                    style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}
                  />
                </Field>
                <Field label="Gender">
                  <select
                    value={gender}
                    onChange={e => setGender(e.target.value)}
                    style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}
                  >
                    <option value="">Prefer not to say</option>
                    <option>Female</option>
                    <option>Male</option>
                    <option>Non-binary</option>
                    <option>Other</option>
                  </select>
                </Field>
              </div>
            </Section>
            <Section title="Professional">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
                <Field label="Occupation">
                  <input value={occupation} onChange={e => setOccupation(e.target.value)} placeholder="e.g. Software Engineer" style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'} />
                </Field>
                <Field label="Job Title">
                  <input value={jobTitle} onChange={e => setJobTitle(e.target.value)} placeholder="e.g. Senior Developer" style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'} />
                </Field>
                <Field label="Organization">
                  <input value={organization} onChange={e => setOrganization(e.target.value)} placeholder="e.g. Accenture" style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'} />
                </Field>
                <Field label="Industry">
                  <input value={industry} onChange={e => setIndustry(e.target.value)} placeholder="e.g. Technology" style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'} />
                </Field>
                <Field label="Education">
                  <input value={education} onChange={e => setEducation(e.target.value)} placeholder="e.g. B.Tech Computer Science" style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'} />
                </Field>
                <Field label="Interests" hint="Comma-separated">
                  <input value={interests} onChange={e => setInterests(e.target.value)} placeholder="e.g. AI, Machine Learning, Chess" style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'} />
                </Field>
              </div>
              <Field label="Short Bio">
                <textarea
                  value={bio}
                  onChange={e => setBio(e.target.value)}
                  rows={3}
                  placeholder="Tell us a little about yourself…"
                  style={{ ...inputStyle, resize: 'vertical' }}
                  onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                  onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}
                />
              </Field>
            </Section>
            <div className="pt-6 flex items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <button
                  onClick={saveProfile}
                  disabled={isSaving || isUploading || hasErrors}
                  className="px-5 py-2 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                >
                  {isSaving ? 'Saving…' : 'Save changes'}
                </button>

                <button
                  onClick={async () => { await logout(); navigate('/login'); }}
                  className="px-5 py-2 rounded-lg text-sm font-medium transition-colors"
                  style={{
                    backgroundColor: 'var(--color-bg-active)',
                    color:           'var(--color-text-primary)',
                    border:          '1px solid var(--color-border)',
                  }}
                >
                  Sign out
                </button>

                {savedMsg && (
                  <span className="text-sm text-emerald-500 flex items-center gap-1.5">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M20 6L9 17l-5-5"/></svg>
                    {savedMsg}
                  </span>
                )}
                {errors.form && <span className="text-sm text-red-500">{errors.form}</span>}
              </div>

              <button
                onClick={deleteAccount}
                className="px-4 py-2 rounded-lg text-sm font-medium text-red-500 hover:text-red-400 transition-colors"
                style={{ border: '1px solid rgba(239,68,68,0.25)', backgroundColor: 'rgba(239,68,68,0.05)' }}
              >
                Delete account
              </button>
            </div>
          </div>
        )}

        {activeTab === 'preferences' && (
          <div className="animate-fade-in-up">
            <div className="pt-10 pb-6">
              <h1 className="text-2xl font-bold" style={{ color: 'var(--color-text-primary)' }}>Preferences</h1>
              <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
                Customize your AI experience and interface.
              </p>
            </div>

            <Section title="AI Configuration">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-5 max-w-lg">
                <Field label="Default AI Model" hint="Used for new chat sessions">
                  <select value={model} onChange={e => setModel(e.target.value)} style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}>
                    {availableModels.length > 0 ? (
                      availableModels.map(m => <option key={m.id} value={m.id}>{m.name}</option>)
                    ) : (
                      <>
                        <option value="GEMINI_2_5_FLASH">Gemini 2.5 Flash</option>
                        <option value="GEMINI_2_5_PRO">Gemini 2.5 Pro</option>
                      </>
                    )}
                  </select>
                </Field>
                <Field label="Response Style" hint="How verbose the AI should be">
                  <select value={responseStyle} onChange={e => setResponseStyle(e.target.value)} style={inputStyle}
                    onFocus={e => e.target.style.borderColor = 'var(--color-accent)'}
                    onBlur={e  => e.target.style.borderColor = 'var(--color-border)'}>
                    <option value="BEGINNER">Beginner</option>
                    <option value="BALANCED">Balanced</option>
                    <option value="CONCISE">Concise</option>
                    <option value="DETAILED">Detailed</option>
                  </select>
                </Field>
              </div>
            </Section>

            <Section title="Appearance">
              <div className="flex items-center justify-between max-w-lg">
                <div>
                  <p className="text-sm font-medium" style={{ color: 'var(--color-text-primary)' }}>Theme</p>
                  <p className="text-xs mt-0.5" style={{ color: 'var(--color-text-tertiary)' }}>
                    Switch between dark and light appearance
                  </p>
                </div>
                <button
                  onClick={toggle}
                  role="switch"
                  aria-checked={theme === 'dark'}
                  className="relative w-12 h-6 rounded-full transition-colors duration-200 focus:outline-none shrink-0"
                  style={{ backgroundColor: theme === 'dark' ? 'var(--color-accent)' : 'var(--color-bg-active)', border: '1px solid var(--color-border)' }}
                >
                  <span
                    className="absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform duration-200"
                    style={{ transform: theme === 'dark' ? 'translateX(24px)' : 'translateX(0)' }}
                  />
                </button>
              </div>

            </Section>
            <div className="pt-6 flex items-center gap-3">
              <button
                onClick={savePreferences}
                className="px-5 py-2 rounded-lg text-sm font-semibold text-white bg-blue-600 hover:bg-blue-500 transition-colors"
              >
                Save preferences
              </button>
              {prefSaved && (
                <span className="text-sm text-emerald-500 flex items-center gap-1.5">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><path d="M20 6L9 17l-5-5"/></svg>
                  Saved!
                </span>
              )}
            </div>
          </div>
        )}

      </div>
    </div>
  );
}