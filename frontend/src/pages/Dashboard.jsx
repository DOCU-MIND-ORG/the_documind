import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import { sessionApi } from '../services/api.js';
import { chatService } from '../services/chatService.js';
import Streaming from '../components/streaming.jsx';

// SVG Icons
const UploadIcon = () => (
  <svg className="w-10 h-10 text-blue-400 mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M12 16.5V9.75m0 0l3 3m-3-3l-3 3M6.75 19.5a4.5 4.5 0 01-1.41-8.775 5.25 5.25 0 0110.233-2.33 3 3 0 013.758 3.848A3.752 3.752 0 0118 19.5H6.75z" />
  </svg>
);

const SendIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
  </svg>
);

const PlusIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
  </svg>
);

const ChatIcon = () => (
  <svg className="w-5 h-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M8.625 9.75a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 01-2.555-.337A5.972 5.972 0 015.41 20.97a5.969 5.969 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
  </svg>
);

const SettingsIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.43l-1.003.828c-.293.241-.438.613-.43.992a7.723 7.723 0 010 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.43l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.991l-1.004-.827a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.28z" />
    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
  </svg>
);

const TrashIcon = () => (
  <svg className="w-4 h-4 text-red-400/80 hover:text-red-400 cursor-pointer transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
  </svg>
);

const MenuIcon = () => (
  <svg className="w-6 h-6 text-[#94a3b8] hover:text-white cursor-pointer" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
  </svg>
);

const CloseIcon = () => (
  <svg className="w-6 h-6 text-[#94a3b8] hover:text-white cursor-pointer" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

const BotAvatar = () => (
  <div className="w-8 h-8 rounded-full bg-blue-500/10 border border-blue-500/30 flex items-center justify-center text-blue-400 shrink-0">
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/>
      <rect x="5" y="8" width="14" height="14" rx="2" ry="2"/>
      <line x1="9" y1="13" x2="9" y2="13.01"/>
      <line x1="15" y1="13" x2="15" y2="13.01"/>
    </svg>
  </div>
);

const UserAvatar = ({ name }) => (
  <div className="w-8 h-8 rounded-full bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center text-indigo-300 font-bold shrink-0 text-sm">
    {name ? name.charAt(0).toUpperCase() : 'U'}
  </div>
);

export default function Dashboard() {
  const { user, logout } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();

  // State
  const [sessions, setSessions] = useState([]);
  const [activeSession, setActiveSession] = useState(null);
  const [messages, setMessages] = useState([]);
  const [suggestions, setSuggestions] = useState([]);
  const [input, setInput] = useState('');
  const [wikiUrl, setWikiUrl] = useState('');

  // Modals & Loaders & Sidebar Drawer
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isLoadingSessions, setIsLoadingSessions] = useState(true);
  const [isUploading, setIsUploading] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [streamingMsgId, setStreamingMsgId] = useState(null);
  const [isDragActive, setIsDragActive] = useState(false);

  // References
  const messagesEndRef = useRef(null);
  const fileInputRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  // Initial load
  useEffect(() => {
    const init = async () => {
      try {
        setIsLoadingSessions(true);
        const data = await sessionApi.getAll();
        setSessions(data);
        if (data.length > 0) {
          handleSelectSession(data[0]);
        }
      } catch (err) {
        showToast(err.message || 'Failed to fetch sessions.', 'error');
      } finally {
        setIsLoadingSessions(false);
      }
    };
    init();
  }, []);

  const handleSelectSession = async (session) => {
    setIsSidebarOpen(false); // Close sidebar drawer on mobile click
    setActiveSession(session);
    setMessages([]);
    setSuggestions([]);
    if (!session) return;
    try {
      const msgs = await sessionApi.getMessages(session.sessionId);
      setMessages(msgs);
      if (session.documentType && msgs.length === 0) {
        const suggs = await sessionApi.getSuggestions(session.sessionId);
        setSuggestions(suggs);
      }
    } catch (err) {
      showToast(err.message || 'Failed to load message history.', 'error');
    }
  };

  const handleCreateSessionDirect = () => {
    const draftSession = {
      sessionId: null,
      title: 'New chat',
      archived: false,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      isDraft: true,
    };

    setActiveSession(draftSession);
    setMessages([]);
    setSuggestions([]);
    setIsSidebarOpen(false);
  };

  const createTitleFromQuery = (query) => {
    const trimmed = query.trim();

    if (trimmed.length <= 10) {
      return trimmed;
    }

    return `${trimmed.slice(0, 10)}...`;
  };

  const handleDeleteSession = async (e, sessionId) => {
    e.stopPropagation();
    if (!window.confirm('Are you sure you want to delete this session?')) return;
    try {
      await sessionApi.delete(sessionId);
      showToast('Session deleted successfully.', 'success');
      setSessions(prev => prev.filter(s => s.sessionId !== sessionId));
      if (activeSession?.sessionId === sessionId) {
        const remaining = sessions.filter(s => s.sessionId !== sessionId);
        if (remaining.length > 0) {
          handleSelectSession(remaining[0]);
        } else {
          setActiveSession(null);
          setMessages([]);
          setSuggestions([]);
        }
      }
    } catch (err) {
      showToast(err.message || 'Failed to delete session.', 'error');
    }
  };

  // Drag & Drop
  const handleDrag = (e) => {
    e.preventDefault();
    e.stopPropagation();
    if (e.type === 'dragenter' || e.type === 'dragover') {
      setIsDragActive(true);
    } else if (e.type === 'dragleave') {
      setIsDragActive(false);
    }
  };

  const handleDrop = async (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragActive(false);
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      const file = e.dataTransfer.files[0];
      await uploadFile(file);
    }
  };

  const handleFileChange = async (e) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      await uploadFile(file);
    }
  };

  const uploadFile = async (file) => {
    if (!activeSession) return;
    if (file.size > 10 * 1024 * 1024) {
      showToast('File too large. Maximum size is 10 MB.', 'error');
      return;
    }
    const extension = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    const supported = ['.pdf', '.txt', '.md', '.png', '.jpg', '.jpeg', '.gif'];
    if (!supported.includes(extension)) {
      showToast('Unsupported format. Supported: PDF, TXT, MD, Images.', 'error');
      return;
    }

    try {
      setIsUploading(true);
      const res = await sessionApi.uploadDocument(activeSession.sessionId, file);
      showToast('Document uploaded and indexed successfully!', 'success');
      
      const updatedSession = {
        ...activeSession,
        documentName: res.documentName,
        documentType: res.documentType
      };
      
      setActiveSession(updatedSession);
      setSessions(prev => prev.map(s => s.sessionId === activeSession.sessionId ? updatedSession : s));
      setSuggestions(res.suggestions || []);
    } catch (err) {
      showToast(err.message || 'Failed to process document.', 'error');
    } finally {
      setIsUploading(false);
    }
  };

  const handleWikipediaSubmit = async (e) => {
    e.preventDefault();
    if (!activeSession || !wikiUrl.trim()) return;
    if (!wikiUrl.toLowerCase().includes('wikipedia.org')) {
      showToast('Please enter a valid Wikipedia URL.', 'error');
      return;
    }

    try {
      setIsUploading(true);
      const res = await sessionApi.loadWikipedia(activeSession.sessionId, wikiUrl.trim());
      showToast('Wikipedia article fetched and indexed successfully!', 'success');
      setWikiUrl('');

      const updatedSession = {
        ...activeSession,
        documentName: res.documentName,
        documentType: res.documentType
      };

      setActiveSession(updatedSession);
      setSessions(prev => prev.map(s => s.sessionId === activeSession.sessionId ? updatedSession : s));
      setSuggestions(res.suggestions || []);
    } catch (err) {
      showToast(err.message || 'Failed to index Wikipedia URL.', 'error');
    } finally {
      setIsUploading(false);
    }
  };

  // Chat actions
  const handleSend = async (e, directText = null) => {
    if (e) e.preventDefault();
    const query = directText || input;
    if (!query.trim() || !activeSession || isStreaming) return;

    let sessionForMessage = activeSession;

    try {
      if (activeSession.isDraft) {
        const title = createTitleFromQuery(query);
        const createdSession = await sessionApi.create(title);

        sessionForMessage = createdSession;

        setActiveSession(createdSession);
        setSessions(prev => [createdSession, ...prev]);
      }

      setInput('');
      setSuggestions([]); // Clear suggestions on send

      const userMsgId = crypto.randomUUID();
      const botMsgId = crypto.randomUUID();

      // Add user message
      setMessages(prev => [...prev, {
        id: userMsgId,
        sender: 'user',
        text: query,
        createdAt: new Date().toISOString()
      }]);

      // Add empty bot message for stream
      setMessages(prev => [...prev, {
        id: botMsgId,
        sender: 'bot',
        text: '',
        createdAt: new Date().toISOString()
      }]);

      setIsStreaming(true);
      setStreamingMsgId(botMsgId);

      await chatService.streamMessage(
          sessionForMessage.sessionId,
          query,
          (chunk) => {
            setMessages(prev => prev.map(msg =>
                msg.id === botMsgId ? {...msg, text: msg.text + chunk} : msg
            ));
          },
          (error) => {
            showToast(error.message || 'Error occurred during streaming.', 'error');
            setIsStreaming(false);
            setStreamingMsgId(null);
          },
          () => {
            setIsStreaming(false);
            setStreamingMsgId(null);
          }
      );
    } catch (error) {
      showToast(error.message || 'Failed to send message.', 'error');
      setIsStreaming(false);
      setStreamingMsgId(null);
    }
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend(e);
    }
  };

  // Citation parser
  const renderCitations = (text) => {
    if (!text) return null;
    const regex = /\[Source:\s*([^\]]+)\]/gi;
    const citations = [];
    let match;
    while ((match = regex.exec(text)) !== null) {
      const citeStr = match[1].trim();
      if (!citations.includes(citeStr)) {
        citations.push(citeStr);
      }
    }

    if (citations.length === 0) return null;

    return (
      <div className="mt-3 flex flex-wrap gap-2 items-center border-t border-white/5 pt-2">
        <span className="text-[10px] text-[#94a3b8] font-semibold uppercase tracking-wider">Sources:</span>
        {citations.map((cite, index) => (
          <span 
            key={index} 
            className="px-2.5 py-0.5 text-xs bg-blue-500/10 border border-blue-500/20 text-blue-400 rounded-md font-medium select-none shadow-sm hover:bg-blue-500/20 transition-all cursor-default"
          >
            {cite}
          </span>
        ))}
      </div>
    );
  };

  // Badge configuration based on source document type
  const getDocBadge = (type) => {
    const badges = {
      pdf: 'bg-red-500/15 border-red-500/30 text-red-400 text-xs font-semibold px-2 py-0.5 rounded-md border shrink-0',
      txt: 'bg-green-500/15 border-green-500/30 text-green-400 text-xs font-semibold px-2 py-0.5 rounded-md border shrink-0',
      md: 'bg-green-500/15 border-green-500/30 text-green-400 text-xs font-semibold px-2 py-0.5 rounded-md border shrink-0',
      image: 'bg-purple-500/15 border-purple-500/30 text-purple-400 text-xs font-semibold px-2 py-0.5 rounded-md border shrink-0',
      wikipedia: 'bg-amber-500/15 border-amber-500/30 text-amber-400 text-xs font-semibold px-2 py-0.5 rounded-md border shrink-0',
    };
    return badges[type?.toLowerCase()] || 'bg-gray-500/15 border-gray-500/30 text-gray-400 text-xs font-semibold px-2 py-0.5 rounded-md border shrink-0';
  };

  const canShowChat = activeSession?.isDraft || activeSession?.documentType;

  return (
    <div className="flex h-screen bg-[#0f1115] text-[#e2e8f0] font-sans relative overflow-hidden">
      {/* Mobile Sidebar Overlay */}
      {isSidebarOpen && (
        <div 
          className="fixed inset-0 bg-black/60 backdrop-blur-sm z-30 md:hidden transition-opacity duration-300"
          onClick={() => setIsSidebarOpen(false)}
        />
      )}

      {/* Sidebar Drawer */}
      <aside className={`fixed inset-y-0 left-0 z-40 md:relative md:flex w-[280px] bg-[#16181d] border-r border-white/5 flex flex-col shrink-0 transition-transform duration-300 ${
        isSidebarOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0'
      }`}>
        {/* Brand header */}
        <div className="h-16 flex items-center px-6 border-b border-white/5 justify-between">
          <div className="text-xl font-bold bg-gradient-to-r from-blue-400 to-indigo-400 bg-clip-text text-transparent tracking-tight">DocuMind</div>
          <button 
            className="md:hidden p-1 hover:bg-white/5 rounded-lg text-[#94a3b8]"
            onClick={() => setIsSidebarOpen(false)}
            aria-label="Close sidebar"
          >
            <CloseIcon />
          </button>
        </div>

        {/* New Session Controls */}
        <div className="p-4">
          <button 
            className="w-full flex items-center justify-between px-4 py-3 bg-white/5 hover:bg-white/10 text-white rounded-xl font-medium border border-white/5 hover:border-white/10 transition-all cursor-pointer shadow-lg active:scale-[0.98]"
            onClick={handleCreateSessionDirect}
          >
            <span>New Session</span>
            <PlusIcon />
          </button>
        </div>

        {/* Sessions List */}
        <div className="flex-1 overflow-y-auto px-2 space-y-1">
          <div className="px-4 py-2 text-xs font-semibold text-[#94a3b8] uppercase tracking-wider">Recent</div>
          
          {isLoadingSessions ? (
            <div className="flex flex-col items-center justify-center py-8 gap-2">
              <div className="w-5 h-5 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
              <span className="text-xs text-[#94a3b8]">Loading...</span>
            </div>
          ) : sessions.length === 0 ? (
            <div className="text-center py-8 text-xs text-[#94a3b8] px-4 leading-normal">
              No sessions found. Create a new session to begin.
            </div>
          ) : (
            sessions.map(session => {
              const isActive = activeSession?.sessionId === session.sessionId;
              return (
                <div 
                  key={session.sessionId}
                  className={`group flex items-center justify-between px-4 py-3 rounded-xl cursor-pointer transition-all ${
                    isActive 
                      ? 'bg-white/[0.07] border border-white/5 text-white font-medium shadow-md' 
                      : 'text-[#94a3b8] hover:bg-white/[0.03] hover:text-white border border-transparent'
                  }`}
                  onClick={() => handleSelectSession(session)}
                >
                  <div className="flex items-center gap-3 min-w-0 flex-1">
                    <ChatIcon />
                    <span className="truncate text-sm">{session.title}</span>
                  </div>
                  <button 
                    className="p-1 hover:bg-white/5 rounded transition-all ml-2"
                    onClick={(e) => handleDeleteSession(e, session.sessionId)}
                    title="Delete session"
                  >
                    <TrashIcon />
                  </button>
                </div>
              );
            })
          )}
        </div>

        {/* User Footer Profile */}
        <div className="p-4 border-t border-white/5 relative bg-[#131519]">
          {isSettingsOpen && (
            <div className="absolute bottom-16 left-4 right-4 bg-[#1e222b] border border-white/5 rounded-xl py-2 shadow-2xl z-50 animate-fade-in-up">
              <button 
                className="w-full text-left px-4 py-2.5 hover:bg-white/5 text-sm text-[#e2e8f0] transition-colors cursor-pointer"
                onClick={() => { setIsSettingsOpen(false); navigate('/settings#profile'); }}
              >
                Profile Settings
              </button>
              <button 
                className="w-full text-left px-4 py-2.5 hover:bg-white/5 text-sm text-[#e2e8f0] transition-colors cursor-pointer"
                onClick={() => { setIsSettingsOpen(false); navigate('/settings#preferences'); }}
              >
                Preferences
              </button>
              <div className="h-px bg-white/5 my-1.5" />
              <button 
                className="w-full text-left px-4 py-2.5 hover:bg-red-500/10 text-sm text-red-400 font-medium transition-colors cursor-pointer"
                onClick={logout}
              >
                Sign Out
              </button>
            </div>
          )}

          <div 
            className="flex items-center justify-between p-2 rounded-xl hover:bg-white/5 cursor-pointer transition-all"
            onClick={() => setIsSettingsOpen(!isSettingsOpen)}
          >
            <div className="flex items-center gap-3 min-w-0">
              <UserAvatar name={user?.name} />
              <div className="min-w-0">
                <div className="text-sm font-semibold text-white truncate">{user?.name || 'User'}</div>
                <div className="text-[11px] text-[#94a3b8] truncate">{user?.email || 'user@example.com'}</div>
              </div>
            </div>
            <button className="text-[#94a3b8] hover:text-white p-1">
              <SettingsIcon />
            </button>
          </div>
        </div>
      </aside>

      {/* Main Panel */}
      <main className="flex-1 flex flex-col overflow-hidden relative" onClick={() => { if (isSettingsOpen) setIsSettingsOpen(false); }}>
        
        {/* Header */}
        <header className="h-16 border-b border-white/5 px-4 sm:px-6 flex items-center justify-between shrink-0 bg-[#121418]">
          <div className="flex items-center gap-2 min-w-0">
            {/* Hamburger menu */}
            <button 
              className="md:hidden p-2 text-[#94a3b8] hover:text-white mr-1 -ml-2 rounded-lg hover:bg-white/5" 
              onClick={() => setIsSidebarOpen(true)}
              aria-label="Open sidebar"
            >
              <MenuIcon />
            </button>

            {activeSession ? (
              <div className="flex items-center gap-2 sm:gap-3 min-w-0">
                <h2 className="font-semibold text-white truncate text-sm sm:text-base shrink-0">{activeSession.title}</h2>
                {activeSession.documentType && (
                  <>
                    <span className="text-[#94a3b8]/50 hidden sm:inline">|</span>
                    <span className={getDocBadge(activeSession.documentType)}>
                      {activeSession.documentType.toUpperCase()}
                    </span>
                    <span className="text-[#e2e8f0] text-xs sm:text-sm truncate max-w-[80px] xs:max-w-[120px] sm:max-w-xs md:max-w-md" title={activeSession.documentName}>
                      {activeSession.documentName}
                    </span>
                  </>
                )}
              </div>
            ) : (
              <h2 className="font-semibold text-white text-base">Workspace</h2>
            )}
          </div>

          {activeSession && !activeSession.isDraft && (
            <button 
              onClick={(e) => handleDeleteSession(e, activeSession.sessionId)}
              className="flex items-center gap-2 px-3 py-1.5 bg-red-500/10 hover:bg-red-500/20 border border-red-500/20 hover:border-red-500/40 text-red-400 rounded-xl text-xs font-semibold cursor-pointer active:scale-95 transition-all shadow-md shrink-0"
              title="Delete current session"
            >
              <TrashIcon />
              <span className="hidden sm:inline">Delete Session</span>
            </button>
          )}
        </header>

        {/* Content area */}
        <div className="flex-1 overflow-hidden flex flex-col bg-[#0d0e12]">
          {!activeSession ? (
            /* Welcome / Empty session state */
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center bg-[radial-gradient(circle_at_50%_40%,rgba(59,130,246,0.05),transparent_40%)]">
              <div className="w-16 h-16 rounded-2xl bg-blue-500/10 border border-blue-500/20 flex items-center justify-center mb-6 text-blue-400 shadow-[0_0_50px_rgba(59,130,246,0.1)]">
                <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
                </svg>
              </div>
              <h1 className="text-2xl sm:text-3xl font-extrabold text-white mb-3 tracking-tight">Point at any document or image.</h1>
              <p className="text-[#94a3b8] max-w-md text-sm sm:text-base leading-relaxed">
                Create a session in the sidebar, feed it a PDF, Image, Markdown file, or Wikipedia link, and ask questions about the contents in natural language.
              </p>
              <button 
                className="mt-8 px-6 py-3 bg-blue-600 hover:bg-blue-500 text-white rounded-xl font-semibold shadow-lg shadow-blue-500/20 cursor-pointer active:scale-95 transition-all text-sm"
                onClick={handleCreateSessionDirect}
              >
                Create new session
              </button>
            </div>
          ) : isUploading ? (
            /* Uploading loading state */
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center bg-[#0d0e12]">
              <div className="w-16 h-16 border-4 border-blue-500/20 border-t-blue-500 rounded-full animate-spin mb-6" />
              <h3 className="text-lg font-semibold text-white mb-2">Ingesting source content...</h3>
              <p className="text-[#94a3b8] text-sm max-w-xs leading-relaxed">
                We are parsing, formatting, and indexing your content into memory. This will take just a few seconds.
              </p>
            </div>
          ) : !canShowChat ? (
            /* Upload Ingestion Panel */
            <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-8 sm:py-12 flex flex-col items-center justify-center bg-[radial-gradient(circle_at_50%_30%,rgba(59,130,246,0.03),transparent_40%)]">
              <div className="w-full max-w-xl text-center mb-6 sm:mb-8 px-2">
                <h1 className="text-2xl font-bold text-white mb-2">Feed DocuMind</h1>
                <p className="text-xs sm:text-sm text-[#94a3b8]">Upload your file or enter a URL to start Q&A analysis.</p>
              </div>

              {/* Drag and Drop Zone */}
              <div 
                className={`relative w-full max-w-xl border-2 border-dashed rounded-2xl p-6 sm:p-10 flex flex-col items-center justify-center gap-2 transition-all duration-300 cursor-pointer shadow-2xl ${
                  isDragActive 
                    ? 'border-blue-500 bg-blue-500/5 shadow-[0_0_50px_rgba(59,130,246,0.12)]' 
                    : 'border-white/10 bg-[#16181d]/50 hover:border-white/20 hover:bg-[#16181d]/85'
                }`}
                onDragEnter={handleDrag}
                onDragOver={handleDrag}
                onDragLeave={handleDrag}
                onDrop={handleDrop}
                onClick={() => fileInputRef.current?.click()}
              >
                <input 
                  type="file" 
                  ref={fileInputRef} 
                  onChange={handleFileChange} 
                  className="hidden" 
                  accept=".pdf,.txt,.md,.png,.jpg,.jpeg,.gif"
                />
                <UploadIcon />
                <span className="text-white font-semibold text-sm text-center">Drag & drop files here, or browse</span>
                <span className="text-xs text-[#94a3b8] mt-1 text-center">PDF, TXT, MD up to 10 MB | PNG, JPG, GIF</span>
              </div>

              {/* OR Divider */}
              <div className="flex items-center w-full max-w-xl my-5 sm:my-6">
                <div className="flex-1 h-px bg-white/5" />
                <span className="px-4 text-xs font-semibold text-[#94a3b8] uppercase tracking-wider">or</span>
                <div className="flex-1 h-px bg-white/5" />
              </div>

              {/* Wikipedia Ingest Input */}
              <form onSubmit={handleWikipediaSubmit} className="w-full max-w-xl flex flex-col sm:flex-row gap-2 sm:gap-3 bg-[#16181d]/50 border border-white/10 p-2 rounded-2xl shadow-xl focus-within:border-blue-500/40 focus-within:ring-4 focus-within:ring-blue-500/5 transition-all">
                <input 
                  type="url"
                  placeholder="Paste Wikipedia URL (e.g., https://en.wikipedia.org/wiki/AI)"
                  className="flex-1 bg-transparent border-none outline-none text-sm text-white px-3 py-2 placeholder-white/25 min-w-0"
                  value={wikiUrl}
                  onChange={(e) => setWikiUrl(e.target.value)}
                  required
                />
                <button 
                  type="submit" 
                  className="bg-blue-600 hover:bg-blue-500 text-white font-semibold text-sm px-6 py-2.5 rounded-xl cursor-pointer transition-colors active:scale-95 whitespace-nowrap"
                >
                  Ingest Link
                </button>
              </form>
            </div>
          ) : (
            /* Active Q&A Interface */
            <div className="flex-1 flex flex-col overflow-hidden relative">
              
              {/* Messages viewport */}
              <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6 sm:py-8 space-y-6">
                {messages.length === 0 ? (
                  /* Empty conversation: Show suggested questions */
                  <div className="h-full flex flex-col items-center justify-center p-4">
                    <div className="text-center max-w-xl mb-6">
                      <h3 className="text-lg font-bold text-white mb-1">
                        {activeSession.isDraft ? 'New chat' : 'Source Initialized!'}
                      </h3>
                      <p className="text-xs text-[#94a3b8]">
                        {activeSession.isDraft
                            ? 'Send your first message to create this session.'
                            : 'Ask anything about your document or choose one of these starter questions:'}
                      </p>
                    </div>

                    {!activeSession.isDraft && suggestions.length > 0 && (
                      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-4 w-full max-w-3xl animate-fade-in-up">
                        {suggestions.map((sugg, idx) => (
                          <div 
                            key={idx}
                            className="bg-[#16181d]/80 border border-white/5 p-4 sm:p-5 rounded-xl text-left text-xs sm:text-sm text-[#e2e8f0] hover:bg-[#1f222b] hover:border-blue-500/30 transition-all duration-300 shadow-md cursor-pointer hover:-translate-y-0.5 active:translate-y-0"
                            onClick={(e) => handleSend(e, sugg)}
                          >
                            <span className="font-medium leading-relaxed block">{sugg}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                ) : (
                  /* Message Logs */
                  messages.map((msg) => {
                    const isBot = msg.sender === 'bot';
                    const isSys = msg.sender === 'system';
                    
                    if (isSys) {
                      return (
                        <div key={msg.id} className="flex justify-center text-center my-4">
                          <span className="px-4 py-1.5 rounded-full bg-white/5 border border-white/5 text-xs text-[#94a3b8] shadow-sm select-none">
                            {msg.text}
                          </span>
                        </div>
                      );
                    }

                    return (
                      <div 
                        key={msg.id}
                        className={`flex items-start gap-2.5 sm:gap-3 max-w-[92%] sm:max-w-[85%] ${
                          isBot ? 'self-start' : 'self-end flex-row-reverse ml-auto'
                        }`}
                      >
                        {isBot ? <BotAvatar /> : <UserAvatar name={user?.name} />}
                        
                        <div className="flex flex-col gap-1 min-w-0">
                          {/* Sender name */}
                          <span className="text-[11px] font-semibold text-[#94a3b8] px-1 uppercase tracking-wider">
                            {isBot ? 'DocuMind AI' : (user?.name || 'You')}
                          </span>
                          
                          {/* Bubble */}
                          <div className={`px-4 sm:px-5 py-3 sm:py-3.5 rounded-2xl shadow-lg leading-relaxed text-sm ${
                            isBot 
                              ? 'bg-[#16181d] border border-white/5 text-[#e2e8f0] rounded-tl-none' 
                              : 'bg-blue-600 text-white rounded-tr-none'
                          }`}>
                            {isBot ? (
                              <Streaming 
                                text={msg.text} 
                                isStreaming={msg.id === streamingMsgId}
                              />
                            ) : (
                              <p className="whitespace-pre-wrap">{msg.text}</p>
                            )}
                            
                            {/* Render Citations if AI message */}
                            {isBot && renderCitations(msg.text)}
                          </div>
                        </div>
                      </div>
                    );
                  })
                )}
                <div ref={messagesEndRef} />
              </div>

              {/* Chat Input Bar */}
              <div className="p-4 sm:p-6 bg-[#0f1115]/80 backdrop-blur-md border-t border-white/5 shrink-0">
                <form 
                  onSubmit={handleSend} 
                  className="max-w-3xl mx-auto flex items-end gap-3 bg-[#16181d] border border-white/10 rounded-2xl px-4 py-2.5 sm:py-3 shadow-lg focus-within:border-blue-500/50 transition-all"
                >
                  <textarea 
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="Ask anything about your document..."
                    className="flex-1 bg-transparent border-0 text-white text-sm outline-none placeholder-white/20 resize-none max-h-32 min-h-[24px] py-1"
                    rows={1}
                    disabled={isStreaming}
                  />
                  <button 
                    type="submit"
                    disabled={!input.trim() || isStreaming}
                    className="p-2 sm:p-2.5 bg-white text-black rounded-xl hover:bg-slate-100 hover:scale-105 active:scale-95 disabled:scale-100 transition-all disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer flex items-center justify-center shrink-0"
                  >
                    <SendIcon />
                  </button>
                </form>
              </div>

            </div>
          )}
        </div>
      </main>


    </div>
  );
}
