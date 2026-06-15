import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import { useLocation, useNavigate } from 'react-router-dom';
import './Settings.css';

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
    <div className="settings-layout">
      <div className="settings-sidebar">
        <div className="settings-brand">
          <button className="back-btn" onClick={() => navigate('/dashboard')}>
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12"></line>
              <polyline points="12 19 5 12 12 5"></polyline>
            </svg>
            Back
          </button>
        </div>
        <div className="settings-nav">
          <button 
            className={`settings-tab ${activeTab === 'profile' ? 'active' : ''}`}
            onClick={() => setActiveTab('profile')}
          >
            Profile
          </button>
          <button 
            className={`settings-tab ${activeTab === 'preferences' ? 'active' : ''}`}
            onClick={() => setActiveTab('preferences')}
          >
            Preferences
          </button>
        </div>
      </div>

      <div className="settings-content">
        <div className="settings-header">
          <h1>{activeTab === 'profile' ? 'Profile' : 'Preferences'}</h1>
          <p className="settings-subtitle">
            {activeTab === 'profile' 
              ? 'Manage your personal information and account security.' 
              : 'Customize your AI experience and interface.'}
          </p>
        </div>

        {activeTab === 'profile' && (
          <div className="settings-card fade-in">
            <div className="settings-section">
              <h3>Personal Information</h3>
              <div className="settings-row">
                <div className="settings-field">
                  <label>Full Name</label>
                  <div className="settings-value">{user?.name || 'TejeshAmbati'}</div>
                </div>
                <div className="settings-field">
                  <label>Email Address</label>
                  <div className="settings-value">{user?.email || 'user@example.com'}</div>
                </div>
              </div>
            </div>

            <div className="settings-divider"></div>

            <div className="settings-section danger-zone">
              <h3>Danger Zone</h3>
              <p className="danger-text">Irreversible and destructive actions.</p>
              
              <div className="danger-actions">
                <button className="btn-logout" onClick={logout}>Sign Out</button>
                <button className="btn-delete" onClick={() => alert('Delete account feature coming soon!')}>
                  Delete Account
                </button>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'preferences' && (
          <div className="settings-card fade-in">
            <div className="settings-section">
              <h3>AI Configuration</h3>
              <div className="settings-form-group">
                <label>Default AI Model</label>
                <select className="settings-input" defaultValue="Aizen Pro">
                  <option>Aizen Pro</option>
                  <option>Aizen Lite</option>
                  <option>Claude 3.5 Sonnet</option>
                </select>
                <span className="settings-help">The model used for new sessions.</span>
              </div>
              
              <div className="settings-form-group">
                <label>Response Style</label>
                <select className="settings-input" defaultValue="Balanced">
                  <option>Balanced</option>
                  <option>Concise</option>
                  <option>Detailed</option>
                </select>
                <span className="settings-help">How verbose the AI should be.</span>
              </div>
            </div>

            <div className="settings-divider"></div>

            <div className="settings-section">
              <h3>Interface</h3>
              <div className="settings-form-group">
                <label>Theme</label>
                <select className="settings-input" defaultValue="Dark Mode">
                  <option>Dark Mode</option>
                  <option>Light Mode</option>
                  <option>System Default</option>
                </select>
              </div>
            </div>
            
            <div className="settings-save-row">
              <button className="btn-save">Save Preferences</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
