import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import { useLocation, useNavigate } from 'react-router-dom';

export default function Settings() {
  const { user, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('profile');

  useEffect(() => {
    if (location.hash === '#preferences') {
      setActiveTab('preferences');
    } else {
      setActiveTab('profile');
    }
  }, [location]);

  return (
    <div className="flex h-screen bg-[#0f1115] text-[#e2e8f0]">
      {/* Settings Sidebar */}
      <div className="w-[260px] bg-[#16181d] border-r border-white/5 p-6 flex flex-col gap-8">
        <div className="flex items-center">
          <button className="flex items-center gap-2 bg-transparent text-[#94a3b8] font-medium p-2 rounded-lg hover:bg-white/5 hover:text-white transition-all cursor-pointer -ml-2" onClick={() => navigate('/dashboard')}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12"></line>
              <polyline points="12 19 5 12 12 5"></polyline>
            </svg>
            Back
          </button>
        </div>
        <div className="flex flex-col gap-2">
          <button 
            className={`text-left bg-transparent text-[#94a3b8] px-4 py-3 rounded-lg font-medium text-sm transition-all cursor-pointer hover:bg-white/5 hover:text-white ${activeTab === 'profile' ? 'bg-white/10 text-white font-semibold' : ''}`}
            onClick={() => setActiveTab('profile')}
          >
            Profile
          </button>
          <button 
            className={`text-left bg-transparent text-[#94a3b8] px-4 py-3 rounded-lg font-medium text-sm transition-all cursor-pointer hover:bg-white/5 hover:text-white ${activeTab === 'preferences' ? 'bg-white/10 text-white font-semibold' : ''}`}
            onClick={() => setActiveTab('preferences')}
          >
            Preferences
          </button>
        </div>
      </div>

      {/* Settings Content */}
      <div className="flex-1 p-16 overflow-y-auto bg-[radial-gradient(circle_at_50%_0%,rgba(59,130,246,0.02),transparent_50%)]">
        <div className="max-w-[800px] mx-auto mb-10">
          <h1 className="text-3xl font-bold text-white mb-2">{activeTab === 'profile' ? 'Profile' : 'Preferences'}</h1>
          <p className="text-[#94a3b8] text-sm sm:text-base">
            {activeTab === 'profile' 
              ? 'Manage your personal information and account security.' 
              : 'Customize your AI experience and interface.'}
          </p>
        </div>

        {activeTab === 'profile' && (
          <div className="max-w-[800px] mx-auto bg-[#16181d]/60 backdrop-blur-xl border border-white/5 rounded-2xl p-10 shadow-2xl animate-fade-in-up">
            <div className="settings-section">
              <h3 className="text-lg font-semibold text-white mb-6">Personal Information</h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-8">
                <div className="flex flex-col gap-2">
                  <label className="text-xs text-[#94a3b8] font-medium">Full Name</label>
                  <div className="text-sm text-[#e2e8f0] bg-black/20 px-4 py-3 rounded-lg border border-white/5">{user?.name || 'TejeshAmbati'}</div>
                </div>
                <div className="flex flex-col gap-2">
                  <label className="text-xs text-[#94a3b8] font-medium">Email Address</label>
                  <div className="text-sm text-[#e2e8f0] bg-black/20 px-4 py-3 rounded-lg border border-white/5">{user?.email || 'user@example.com'}</div>
                </div>
              </div>
            </div>

            <div className="h-px bg-white/5 my-10"></div>

            <div className="settings-section danger-zone">
              <h3 className="text-lg font-semibold text-red-500 mb-2">Danger Zone</h3>
              <p className="text-[#94a3b8] text-xs sm:text-sm mb-6">Irreversible and destructive actions.</p>
              
              <div className="flex gap-4">
                <button className="bg-white/5 text-white border border-white/10 px-6 py-3 rounded-lg font-medium transition-all hover:bg-white/10 cursor-pointer" onClick={logout}>Sign Out</button>
                <button className="bg-red-500/10 text-red-500 border border-red-500/20 px-6 py-3 rounded-lg font-medium transition-all hover:bg-red-500 hover:text-white cursor-pointer" onClick={() => alert('Delete account feature coming soon!')}>
                  Delete Account
                </button>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'preferences' && (
          <div className="max-w-[800px] mx-auto bg-[#16181d]/60 backdrop-blur-xl border border-white/5 rounded-2xl p-10 shadow-2xl animate-fade-in-up">
            <div className="settings-section">
              <h3 className="text-lg font-semibold text-white mb-6">AI Configuration</h3>
              <div className="flex flex-col gap-2 mb-6 max-w-[400px]">
                <label className="text-xs text-[#94a3b8] font-medium">Default AI Model</label>
                <select className="bg-black/20 border border-white/10 text-white px-4 py-3 rounded-lg text-sm outline-none transition-all focus:border-blue-500" defaultValue="Aizen Pro">
                  <option>Aizen Pro</option>
                  <option>Aizen Lite</option>
                  <option>Claude 3.5 Sonnet</option>
                </select>
                <span className="text-[11px] text-[#94a3b8] mt-1">The model used for new sessions.</span>
              </div>
              
              <div className="flex flex-col gap-2 mb-6 max-w-[400px]">
                <label className="text-xs text-[#94a3b8] font-medium">Response Style</label>
                <select className="bg-black/20 border border-white/10 text-white px-4 py-3 rounded-lg text-sm outline-none transition-all focus:border-blue-500" defaultValue="Balanced">
                  <option>Balanced</option>
                  <option>Concise</option>
                  <option>Detailed</option>
                </select>
                <span className="text-[11px] text-[#94a3b8] mt-1">How verbose the AI should be.</span>
              </div>
            </div>

            <div className="h-px bg-white/5 my-10"></div>

            <div className="settings-section">
              <h3 className="text-lg font-semibold text-white mb-6">Interface</h3>
              <div className="flex flex-col gap-2 mb-6 max-w-[400px]">
                <label className="text-xs text-[#94a3b8] font-medium">Theme</label>
                <select className="bg-black/20 border border-white/10 text-white px-4 py-3 rounded-lg text-sm outline-none transition-all focus:border-blue-500" defaultValue="Dark Mode">
                  <option>Dark Mode</option>
                  <option>Light Mode</option>
                  <option>System Default</option>
                </select>
              </div>
            </div>
            
            <div className="mt-8 flex justify-end">
              <button className="bg-white text-black px-8 py-3 rounded-lg font-semibold transition-colors hover:bg-slate-100 cursor-pointer">Save Preferences</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
