/**
 * Dashboard.jsx — Full rewrite to wire up RAG backend.
 *
 * CHANGES vs original:
 *  1. Sessions now have real UUIDs (crypto.randomUUID) passed to every API call
 *  2. handleFileUpload replaced with UploadPanel — calls real /ingest/* endpoints
 *  3. handleSend calls queryApi.ask() (RAG) not chatService.streamMessage
 *     → streaming still available via chatService.streamMessage for /chat/stream
 *  4. Bot messages now carry citations array → CitationCard renders source chips
 *  5. All 4 error states shown in chat as "system-error" messages
 *  6. Wikipedia URL input via UploadPanel's wikipedia tab
 *  7. Session switch correctly isolates documents per sessionId
 */

import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { chatService } from '../services/chatService.js';
import { queryApi } from '../services/api.js';
import UploadPanel from '../components/UploadPanel.jsx';
import CitationCard from '../components/CitationCard.jsx';
import Streaming from '../components/streaming.jsx';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './Dashboard.css';

// ── Icons (kept from original) ───────────────────────────────────────────────

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
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

const BotAvatar = () => (
  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
    <rect x="5" y="8" width="14" height="14" rx="2" />
    <line x1="9" y1="13" x2="9" y2="13.01" />
    <line x1="15" y1="13" x2="15" y2="13.01" />
  </svg>
);

// ── Helpers ──────────────────────────────────────────────────────────────────

function createSession(name) {
  return { id: crypto.randomUUID(), name, messages: [], documents: [] };
}

function systemMsg(text, isError = false) {
  return { id: crypto.randomUUID(), text, sender: isError ? 'system-error' : 'system' };
}

// ── Component ────────────────────────────────────────────────────────────────

export default function Dashboard() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  // Sessions — each has a UUID, its own message list and ingested doc list
  const [sessions, setSessions]             = useState([createSession('New Chat')]);
  const [activeSessionId, setActiveSessionId] = useState(sessions[0].id);

  const [input, setInput]                   = useState('');
  const [isLoading, setIsLoading]           = useState(false);
  const [streamingMsgId, setStreamingMsgId] = useState(null);
  const [isUploadOpen, setIsUploadOpen]     = useState(false);
  const [isSessionModalOpen, setIsSessionModalOpen] = useState(false);
  const [newSessionName, setNewSessionName] = useState('');
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  const messagesEndRef = useRef(null);

  // Active session helpers
  const activeSession = sessions.find(s => s.id === activeSessionId) || sessions[0];

  function updateSession(id, updater) {
    setSessions(prev => prev.map(s => s.id === id ? updater(s) : s));
  }

  function addMessage(sessionId, msg) {
    updateSession(sessionId, s => ({ ...s, messages: [...s.messages, msg] }));
  }

  function updateLastBotMessage(sessionId, updater) {
    updateSession(sessionId, s => {
      const msgs = [...s.messages];
      const lastBotIdx = [...msgs].reverse().findIndex(m => m.sender === 'bot');
      if (lastBotIdx === -1) return s;
      const realIdx = msgs.length - 1 - lastBotIdx;
      msgs[realIdx] = updater(msgs[realIdx]);
      return { ...s, messages: msgs };
    });
  }

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [activeSession.messages]);

  // ── Send message ─────────────────────────────────────────────────────────

  const handleSend = async (e) => {
    e.preventDefault();
    const question = input.trim();
    if (!question || isLoading) return;

    const sessionId = activeSession.id;
    setInput('');
    setIsLoading(true);

    // Check documents uploaded — Error state 4: no source loaded
    // if (activeSession.documents.length === 0) {
    //   addMessage(sessionId, systemMsg(
    //     '⚠️ No documents have been uploaded to this session yet. ' +
    //     'Please upload a file or add a Wikipedia URL first.', true
    //   ));
    //   setIsLoading(false);
    //   return;
    // }

    addMessage(sessionId, { id: crypto.randomUUID(), text: question, sender: 'user' });

    // Use RAG query endpoint — returns answer + citations
    try {
      const result = await queryApi.ask(question, sessionId);

      const botMsg = {
        id: crypto.randomUUID(),
        text: result.answer,
        sender: 'bot',
        citations: result.citations || [],
        foundInDocuments: result.foundInDocuments,
      };
      addMessage(sessionId, botMsg);
    } catch (err) {
      addMessage(sessionId, systemMsg(`❌ ${err.message}`, true));
    } finally {
      setIsLoading(false);
    }
  };

  // ── Streaming send (optional — uses /chat/stream not RAG) ────────────────

  const handleStreamSend = async (e) => {
    e.preventDefault();
    const question = input.trim();
    if (!question || isLoading) return;

    const sessionId = activeSession.id;
    const botMsgId  = crypto.randomUUID();

    setInput('');
    setIsLoading(true);
    addMessage(sessionId, { id: crypto.randomUUID(), text: question, sender: 'user' });
    addMessage(sessionId, { id: botMsgId, text: '', sender: 'bot', citations: [] });
    setStreamingMsgId(botMsgId);

    await chatService.streamMessage(
      question,
      sessionId,
      (chunk) => {
        updateSession(sessionId, s => ({
          ...s,
          messages: s.messages.map(m =>
            m.id === botMsgId ? { ...m, text: m.text + chunk } : m
          ),
        }));
      },
      (err) => addMessage(sessionId, systemMsg(`❌ Stream error: ${err.message}`, true)),
      () => { setIsLoading(false); setStreamingMsgId(null); }
    );
  };

  // ── Upload callbacks ─────────────────────────────────────────────────────

  function onIngestSuccess(result) {
    updateSession(activeSession.id, s => ({
      ...s,
      documents: [...s.documents, { id: result.documentId, name: result.fileName, type: result.sourceType }],
    }));
    addMessage(activeSession.id, systemMsg(
      `✓ Uploaded "${result.fileName}" — ${result.chunksCreated} chunks indexed and ready.`
    ));
    setIsUploadOpen(false);
  }

  function onIngestError(message) {
    addMessage(activeSession.id, systemMsg(`❌ ${message}`, true));
    setIsUploadOpen(false);
  }

  // ── Session management ───────────────────────────────────────────────────

  function createNewSession(e) {
    e.preventDefault();
    const name = newSessionName.trim() || 'New Chat';
    const session = createSession(name);
    setSessions(prev => [session, ...prev]);
    setActiveSessionId(session.id);
    setNewSessionName('');
    setIsSessionModalOpen(false);
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(e); }
  };

  // ── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="dashboard-layout">

      {/* Sidebar */}
      <aside className="chat-sidebar">
        <div className="sidebar-header">
          <div className="brand-title">DocMind</div>
        </div>

        <button className="new-chat-btn" onClick={() => setIsSessionModalOpen(true)}>
          <span>New Session</span>
          <PlusIcon />
        </button>

        <div className="session-list">
          <div className="sidebar-heading">Sessions</div>
          {sessions.map(session => (
            <div
              key={session.id}
              className={`session-item ${session.id === activeSessionId ? 'active' : ''}`}
              onClick={() => setActiveSessionId(session.id)}
            >
              <ChatIcon />
              <span className="session-name">{session.name}</span>
              {/* Doc count badge */}
              {session.documents.length > 0 && (
                <span style={badgeStyle}>{session.documents.length}</span>
              )}
            </div>
          ))}
        </div>

        <div className="sidebar-footer">
          {isSettingsOpen && (
            <div className="settings-popover">
              <button className="settings-menu-item" onClick={() => { setIsSettingsOpen(false); navigate('/settings#profile'); }}>Profile</button>
              <button className="settings-menu-item" onClick={() => { setIsSettingsOpen(false); navigate('/settings#preferences'); }}>Preferences</button>
              <div className="settings-divider" />
              <button className="settings-logout-btn" onClick={logout}>Sign Out</button>
            </div>
          )}
          <div className="user-profile" onClick={() => setIsSettingsOpen(!isSettingsOpen)}>
            <div className="user-info">
              <div className="user-avatar">{user?.name ? user.name.charAt(0).toUpperCase() : '?'}</div>
              <div className="user-name">{user?.name || 'User'}</div>
            </div>
            <button className="settings-btn"><SettingsIcon /></button>
          </div>
        </div>
      </aside>

      {/* Main chat area */}
      <main className="chat-main" onClick={() => { if (isSettingsOpen) setIsSettingsOpen(false); }}>

        {/* Upload panel (collapsible) */}
        {isUploadOpen && (
          <div style={uploadPanelStyle}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
              <span style={{ fontWeight: 600, fontSize: '14px' }}>Add source to this session</span>
              <button onClick={() => setIsUploadOpen(false)} style={closeBtn}>✕</button>
            </div>
            <UploadPanel
              sessionId={activeSession.id}
              onSuccess={onIngestSuccess}
              onError={onIngestError}
            />
            {activeSession.documents.length > 0 && (
              <div style={{ marginTop: '10px' }}>
                <div style={{ fontSize: '12px', fontWeight: 600, color: '#888', marginBottom: '4px' }}>Indexed in this session:</div>
                {activeSession.documents.map(d => (
                  <div key={d.id} style={{ fontSize: '12px', color: '#555' }}>
                    {d.type === 'pdf' ? '📄' : d.type === 'image' ? '🖼️' : d.type === 'wikipedia' ? '🌐' : '📝'} {d.name}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Messages */}
        <div className="messages-area">
          {activeSession.messages.length === 0 && (
            <div style={emptyState}>
              <div style={{ fontSize: '32px', marginBottom: '8px' }}>📂</div>
              <p style={{ margin: 0, color: '#888', fontSize: '14px' }}>
                Upload a document or paste a Wikipedia link to get started.
              </p>
              <button style={uploadCta} onClick={() => setIsUploadOpen(true)}>
                ⬆ Add a source
              </button>
            </div>
          )}

          {activeSession.messages.map(msg => (
            <div key={msg.id} className={`message-wrapper ${msg.sender}`}>
              {msg.sender !== 'system' && msg.sender !== 'system-error' && (
                <div className="message-avatar">
                  {msg.sender === 'bot' ? <BotAvatar /> : (user?.name?.charAt(0).toUpperCase() || '?')}
                </div>
              )}
              <div className="message-content">
                {msg.sender !== 'system' && msg.sender !== 'system-error' && (
                  <div className="message-sender">
                    {msg.sender === 'bot' ? 'DocMind' : (user?.name || 'You')}
                  </div>
                )}
                <div className={`message-text ${msg.sender === 'system-error' ? 'error-msg' : ''}`}>
                  {msg.sender === 'system' || msg.sender === 'system-error' ? (
                    msg.text
                  ) : msg.sender === 'user' ? (
                    msg.text
                  ) : (
                    <>
                      <Streaming text={msg.text} isStreaming={msg.id === streamingMsgId} />
                      {/* Citations — only show when not streaming */}
                      {msg.id !== streamingMsgId && (
                        <CitationCard citations={msg.citations} />
                      )}
                    </>
                  )}
                </div>
              </div>
            </div>
          ))}

          {isLoading && !streamingMsgId && (
            <div className="message-wrapper bot">
              <div className="message-avatar"><BotAvatar /></div>
              <div className="message-content">
                <div className="message-sender">DocMind</div>
                <div className="message-text" style={{ color: '#888', fontStyle: 'italic' }}>Searching documents…</div>
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Input area */}
        <div className="chat-input-container">
          <form onSubmit={handleSend} className="chat-input-wrapper">
            {/* Upload toggle button */}
            <button
              type="button"
              className="file-upload-btn"
              title="Upload document or add Wikipedia URL"
              onClick={() => setIsUploadOpen(o => !o)}
              style={isUploadOpen ? { color: 'var(--color-accent, #4f46e5)' } : {}}
            >
              <svg className="icon" viewBox="0 0 24 24">
                <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
              </svg>
            </button>

            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={
                activeSession.documents.length === 0
                  ? 'Upload a document first…'
                  : 'Ask anything about your documents…'
              }
              className="chat-text-input"
              rows={1}
              disabled={isLoading}
            />

            <button type="submit" className="chat-send-btn" disabled={!input.trim() || isLoading}>
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
                onChange={e => setNewSessionName(e.target.value)}
              />
              <div className="modal-actions">
                <button type="button" className="modal-btn-cancel" onClick={() => setIsSessionModalOpen(false)}>Cancel</button>
                <button type="submit" className="modal-btn-create">Create</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Inline styles for new elements (reuse Dashboard.css for existing classes) ─

const badgeStyle = {
  marginLeft: 'auto', background: 'var(--color-accent, #4f46e5)',
  color: '#fff', borderRadius: '999px', padding: '1px 7px', fontSize: '11px',
};

const uploadPanelStyle = {
  margin: '12px 16px 0',
  padding: '16px',
  border: '1px solid var(--color-border-secondary, #e0e0e0)',
  borderRadius: '12px',
  background: 'var(--color-bg-primary, #fff)',
};

const closeBtn = {
  background: 'none', border: 'none', cursor: 'pointer', fontSize: '16px', color: '#888',
};

const emptyState = {
  display: 'flex', flexDirection: 'column', alignItems: 'center',
  justifyContent: 'center', height: '100%', gap: '12px',
};

const uploadCta = {
  marginTop: '8px', padding: '9px 20px', borderRadius: '8px', border: 'none',
  background: 'var(--color-accent, #4f46e5)', color: '#fff',
  fontWeight: 600, fontSize: '14px', cursor: 'pointer',
};
