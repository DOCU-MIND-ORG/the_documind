import React, { useState, useRef, useEffect } from 'react';
import { useTheme } from '../context/ThemeContext.jsx';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { chatService } from '../services/chatService.js';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import Streaming from '../components/streaming.jsx';
import './Dashboard.css';

// SVG Icons
const UploadIcon = () => (
  <svg className="icon" viewBox="0 0 24 24">
    <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
  </svg>
);

const SendIcon = () => (
  <svg className="icon" viewBox="0 0 24 24" style={{ transform: 'translateX(2px)' }}>
    <line x1="22" y1="2" x2="11" y2="13" />
    <polygon points="22 2 15 22 11 13 2 9 22 2" />
  </svg>
);

const PlusIcon = () => (
  <svg className="icon" viewBox="0 0 24 24">
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </svg>
);

const ChatIcon = () => (
  <svg className="icon" viewBox="0 0 24 24">
    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
  </svg>
);

const SettingsIcon = () => (
  <svg className="icon" viewBox="0 0 24 24">
    <circle cx="12" cy="12" r="3"></circle>
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
  </svg>
);

const BotAvatar = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <path d="M12 2a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/>
    <rect x="5" y="8" width="14" height="14" rx="2" ry="2"/>
    <line x1="9" y1="13" x2="9" y2="13.01"/>
    <line x1="15" y1="13" x2="15" y2="13.01"/>
  </svg>
);

export default function Dashboard() {
  const { user, logout, updateUser } = useAuth();
  const { theme, toggle } = useTheme();
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  
  // Chat State
  const [messages, setMessages] = useState([
    { id: 1, text: "Hello! I'm Aizen Sosuke. Upload a document to get started.", sender: "bot" }
  ]);
  const [input, setInput] = useState('');
  const [sessions, setSessions] = useState([
    { id: 1, name: "Project Requirements" },
    { id: 2, name: "Financial Report 2025" }
  ]);
  
  // Modal & Popover State
  const [isSessionModalOpen, setIsSessionModalOpen] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [streamingMsgId, setStreamingMsgId] = useState(null);
  
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  useEffect(() => {
    const onUpdated = (e) => { if (e?.detail) updateUser(e.detail); };
    window.addEventListener('profile-updated', onUpdated);
    return () => window.removeEventListener('profile-updated', onUpdated);
  }, [updateUser]);

  const handleSend = async (e) => {
    e.preventDefault();
    if (!input.trim() || isLoading) return;
    
    const userMsg = input;
    const userMsgId = crypto.randomUUID();
    const botMsgId = crypto.randomUUID();
    
    setMessages(prev => [...prev, { id: userMsgId, text: userMsg, sender: "user" }]);
    setInput('');
    setIsLoading(true);
    
    // Create an empty bot message immediately
    setMessages(prev => [...prev, { id: botMsgId, text: '', sender: "bot" }]);
    setStreamingMsgId(botMsgId);
    
    try {
      await chatService.streamMessage(
        userMsg,
        (chunk) => {
          // Dynamically append incoming text chunks to the correct bot message
          setMessages(prev => prev.map(msg => 
            msg.id === botMsgId ? { ...msg, text: msg.text + chunk } : msg
          ));
        },
        (error) => {
          setMessages(prev => [...prev, { 
            id: Date.now(), 
            text: "Error: Stream interrupted or unable to connect.", 
            sender: "system" 
          }]);
        },
        () => {
          // onComplete - nothing extra to do since text is already appended
        }
      );
    } catch (error) {
      console.error(error);
    } finally {
      setIsLoading(false);
    setStreamingMsgId(null); 
    }
  };

  const handleFileUpload = (e) => {
    const file = e.target.files[0];
    if (file) {
      setMessages(prev => [...prev, { id: Date.now(), text: `Uploaded document: ${file.name}`, sender: "system" }]);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend(e);
    }
  };

  const createNewSession = (e) => {
    e.preventDefault();
    if (!newSessionName.trim()) return;
    
    setSessions([{ id: Date.now(), name: newSessionName }, ...sessions]);
    setMessages([{ id: Date.now(), text: `Started new session: ${newSessionName}`, sender: "system" }]);
    setNewSessionName('');
    setIsSessionModalOpen(false);
  };

  return (
    <div className="dashboard-layout">
      {/* Sidebar */}
      <aside className={`chat-sidebar ${collapsed ? 'collapsed' : ''}`}>
        <div className="sidebar-header">
          <div className="brand-title">Soul Society</div>
          <div className="header-controls" style={{display:'flex', alignItems:'center', gap:8}}>
            <button onClick={() => setCollapsed(c => !c)} className="p-1 text-muted hover:text-main">{collapsed ? '▶' : '◀'}</button>
          </div>
        </div>
        
        <button className="new-chat-btn" onClick={() => setIsSessionModalOpen(true)}>
          <span>New Session</span>
          <PlusIcon />
        </button>
        
        <div className="session-list">
          <div className="sidebar-heading">Recent</div>
          {sessions.map(session => (
            <div key={session.id} className="session-item">
              <ChatIcon />
              <span className="session-name">{session.name}</span>
            </div>
          ))}
        </div>
        
        {/* User Profile & Settings Area */}
        <div className="sidebar-footer">
          {/* Settings Popover */}
          {isSettingsOpen && (
            <div className="settings-popover">
              
              <button 
                className="settings-menu-item" 
                onClick={() => { setIsSettingsOpen(false); navigate('/settings#profile'); }}
              >
                Profile
              </button>
              
              <button 
                className="settings-menu-item" 
                onClick={() => { setIsSettingsOpen(false); navigate('/settings#preferences'); }}
              >
                Preferences
              </button>

              <div className="settings-divider"></div>
              
              <button className="settings-logout-btn" onClick={logout}>
                Sign Out
              </button>
            </div>
          )}

          <div className="user-profile" onClick={() => setIsSettingsOpen(!isSettingsOpen)}>
            <div className="user-info">
              <div className="user-avatar">
                {user?.name ? user.name.charAt(0).toUpperCase() : 'T'}
              </div>
              <div className="user-name">{user?.name || 'TejeshAmbati'}</div>
            </div>
            <button className="settings-btn" title="Settings">
              <SettingsIcon />
            </button>
          </div>
        </div>
      </aside>

      {/* Main Chat Area */}
      <main className="chat-main" onClick={() => { if(isSettingsOpen) setIsSettingsOpen(false); }}>
        <div className="messages-area">
          {messages.map(msg => (
            <div key={msg.id} className={`message-wrapper ${msg.sender}`}>
              {msg.sender !== 'system' && (
                <div className="message-avatar">
                  {msg.sender === 'bot' ? <BotAvatar /> : (user?.name ? user.name.charAt(0).toUpperCase() : 'T')}
                </div>
              )}
              <div className="message-content">
                {msg.sender !== 'system' && (
                  <div className="message-sender">
                    {msg.sender === 'bot' ? 'Aizen' : (user?.name || 'TejeshAmbati')}
                  </div>
                )}
                <div className="message-text">
                  {msg.sender === 'system' || msg.sender === 'user' ? (
                    msg.text
                  ) : (
                  <Streaming
  text={msg.text}
  isStreaming={msg.id === streamingMsgId}
/>
                  )}
                </div>
              </div>
            </div>
          ))}
          <div ref={messagesEndRef} />
        </div>
        
        {/* Input Area */}
        <div className="chat-input-container">
          <form onSubmit={handleSend} className="chat-input-wrapper">
            <label className="file-upload-btn" title="Upload Document">
              <input type="file" onChange={handleFileUpload} className="hidden-file-input" />
              <UploadIcon />
            </label>
            <textarea 
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="Ask anything about your documents..." 
              className="chat-text-input"
              rows={1}
            />
            <button 
              type="submit" 
              className="chat-send-btn"
              disabled={!input.trim()}
            >
              <SendIcon />
            </button>
          </form>
        </div>
      </main>

      {/* New Session Modal */}
      {isSessionModalOpen && (
        <div className="modal-overlay" onClick={() => setIsSessionModalOpen(false)}>
          <div className="modal-content" onClick={e => e.stopPropagation()}>
            <h2 className="modal-title">Create New Session</h2>
            <form onSubmit={createNewSession}>
              <input
                type="text"
                autoFocus
                className="modal-input"
                placeholder="E.g. Q3 Financial Analysis"
                value={newSessionName}
                onChange={(e) => setNewSessionName(e.target.value)}
              />
              <div className="modal-actions">
                <button type="button" className="modal-btn-cancel" onClick={() => setIsSessionModalOpen(false)}>
                  Cancel
                </button>
                <button type="submit" className="modal-btn-create" disabled={!newSessionName.trim()}>
                  Create
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
