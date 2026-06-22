import { useReducer, useRef, useEffect, useCallback, useState } from 'react';
import { useParams, useNavigate, useOutletContext } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { chatService } from '../services/chatService.js';
import { attachmentService } from '../services/attachmentService.js';
import { useToast } from '../context/ToastContext.jsx';
import { preferenceService } from '../services/preferenceService.js';
import Streaming from '../components/streaming.jsx';
import CitationDrawer from '../components/CitationDrawer.jsx';
import { chatReducer, initialChatState } from '../state/chatReducer.js';


const SendIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
  </svg>
);

const PaperclipIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
  </svg>
);

const LinkIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
  </svg>
);

const XIcon = () => (
  <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

const BotAvatar = () => (
  <div className="w-7 h-7 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shrink-0 shadow-lg shadow-blue-500/25">
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 2a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" />
      <rect x="5" y="8" width="14" height="14" rx="2" />
      <line x1="9" y1="13" x2="9" y2="13.01" />
      <line x1="15" y1="13" x2="15" y2="13.01" />
    </svg>
  </div>
);

const formatBytes = (bytes) => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

// The backend saves a synthetic message like "[file upload: resume.pdf]" or
// "[wikipedia link: Some_Page]" purely as a row to anchor the Attachment
// record to (it needs a Message to attach to). It's plumbing, not something
// the user typed or the assistant said, so it should never show up as its own
// chat bubble — the upload itself is already visible via the pending-file
// chips while it's happening.
const UPLOAD_ANCHOR_PATTERN = /^\[(file upload|wikipedia link): .+\]$/;
const isUploadAnchorMessage = (msg) => UPLOAD_ANCHOR_PATTERN.test((msg.text || '').trim());

export default function Chat() {
  const { sessionId: routeSessionId } = useParams();
  const navigate                      = useNavigate();
  const { user }                      = useAuth();
  const { sessions, addSession }      = useSessions();
  const { showToast }                 = useToast();
  const { openMobileSidebar }         = useOutletContext();

  const [state, dispatch] = useReducer(chatReducer, {
    ...initialChatState,
    sessionId: routeSessionId ?? null,
  });

  const [input, setInput]             = useState('');
  const [isDragging, setIsDragging]   = useState(false);
  const [pendingFiles, setPendingFiles] = useState([]);
  const [activeCitation, setActiveCitation] = useState(null);
  const [showWikiInput, setShowWikiInput] = useState(false);
  const [wikiUrl, setWikiUrl] = useState('');
  const bottomRef    = useRef(null);
  const textareaRef  = useRef(null);
  const fileInputRef = useRef(null);
  const dragCounterRef = useRef(0);

  const [greetingIndex] = useState(() => Math.floor(Math.random() * 12));
  const [modelMenuOpen, setModelMenuOpen] = useState(false);
  const [selectedModel, setSelectedModel] = useState(
    () => localStorage.getItem('selectedModel') || 'GEMINI_2_5_PRO'
  );
  const [models, setModels] = useState([]);
  const suggestionsPollRef = useRef(null);

  useEffect(() => {
    const fetchModels = async () => {
      try {
        const availableModels = await preferenceService.getModels();
        setModels(availableModels);
      } catch (err) {
        console.error("Failed to load models", err);
      }
    };
    fetchModels();
  }, []);

  const handleModelSelect = (id) => {
    setSelectedModel(id);
    localStorage.setItem('selectedModel', id);
    setModelMenuOpen(false);
  };

  const selectedModelObj = models.find(m => m.id === selectedModel);
  const displayModelName = selectedModelObj ? selectedModelObj.name : 'Loading...';

  const session = sessions.find(s => String(s.sessionId) === String(state.sessionId));

  useEffect(() => {
    dispatch({ type: 'SYNC_ROUTE_SESSION', payload: { sessionId: routeSessionId ?? null } });
  }, [routeSessionId]);

  useEffect(() => {
    if (!state.sessionId) return;
    if (state.messages.length > 0) return;
    let cancelled = false;
    dispatch({ type: 'MESSAGES_LOADING', sessionId: state.sessionId });
    sessionService.getMessages(state.sessionId)
      .then(msgs => { if (!cancelled) dispatch({ type: 'MESSAGES_LOADED', sessionId: state.sessionId, payload: { messages: msgs } }); })
      .catch(err  => { if (!cancelled) { dispatch({ type: 'MESSAGES_LOAD_FAILED', sessionId: state.sessionId }); showToast(err.message || 'Failed to load messages', 'error'); } });
    return () => { cancelled = true; };
  }, [state.sessionId]);

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [state.messages]);

  // Polls GET /api/sessions/{id}/suggested-questions every 2s until ingestion's
  // question-generation step reports READY or FAILED, then stops. Started after
  // a successful file/Wikipedia upload (see uploadPendingFiles / handleWikiSubmit).
  const pollSuggestedQuestions = useCallback((sessionId) => {
    if (suggestionsPollRef.current) {
      clearInterval(suggestionsPollRef.current);
      suggestionsPollRef.current = null;
    }

    const poll = async () => {
      try {
        const res = await attachmentService.getSuggestedQuestions(sessionId);
        if (res.status === 'READY') {
          dispatch({ type: 'SET_SUGGESTED_QUESTIONS', sessionId, payload: { questions: res.questions || [] } });
          clearInterval(suggestionsPollRef.current);
          suggestionsPollRef.current = null;
        } else if (res.status === 'FAILED') {
          clearInterval(suggestionsPollRef.current);
          suggestionsPollRef.current = null;
        }
        // NOT_STARTED / GENERATING: keep polling
      } catch {
        // Transient network errors shouldn't kill polling permanently, but stop
        // after this interval's tick — the next tick will simply retry.
      }
    };

    poll();
    suggestionsPollRef.current = setInterval(poll, 2000);
  }, []);

  useEffect(() => {
    return () => {
      if (suggestionsPollRef.current) {
        clearInterval(suggestionsPollRef.current);
        suggestionsPollRef.current = null;
      }
    };
  }, [state.sessionId]);

  const onDragEnter = useCallback((e) => { e.preventDefault(); dragCounterRef.current += 1; if (dragCounterRef.current === 1) setIsDragging(true); }, []);
  const onDragLeave = useCallback((e) => { e.preventDefault(); dragCounterRef.current -= 1; if (dragCounterRef.current === 0) setIsDragging(false); }, []);
  const onDragOver  = useCallback((e) => { e.preventDefault(); }, []);
  const onDrop      = useCallback((e) => { e.preventDefault(); dragCounterRef.current = 0; setIsDragging(false); const files = Array.from(e.dataTransfer.files); if (files.length > 0) addFiles(files); }, []);

  const addFiles = useCallback((files) => {
    setPendingFiles(prev => [...prev, ...files.map(f => ({ file: f, name: f.name, status: 'pending' }))]);
  }, []);

  const removePendingFile = useCallback((index) => {
    setPendingFiles(prev => prev.filter((_, i) => i !== index));
  }, []);

  const uploadPendingFiles = useCallback(async (sessionId) => {
    const toUpload = pendingFiles.filter(f => f.status === 'pending');
    if (toUpload.length === 0) return;
    for (let i = 0; i < pendingFiles.length; i++) {
      if (pendingFiles[i].status !== 'pending') continue;
      setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'uploading' } : f));
      try {
        await attachmentService.upload(sessionId, pendingFiles[i].file);
        setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'done' } : f));
      } catch {
        setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'error' } : f));
        showToast(`Failed to upload ${pendingFiles[i].name}`, 'error');
      }
    }
    setTimeout(() => setPendingFiles(prev => prev.filter(f => f.status !== 'done')), 2000);
    pollSuggestedQuestions(sessionId);
  }, [pendingFiles, showToast, pollSuggestedQuestions]);

  const handleSend = useCallback(async (e, overrideQuery) => {
    if (e) e.preventDefault();
    const query = (overrideQuery ?? input).trim();
    const hasPendingFiles = pendingFiles.some(f => f.status === 'pending');
    if (!query && !hasPendingFiles) return;
    if (state.isStreaming) return;
    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    let activeSessionId = state.sessionId;
    if (!activeSessionId) {
      try {
        let title = query.length <= 40 ? query : query.slice(0, 40) + '…';
        if (!title) {
          title = 'New Chat - ' + new Date().toLocaleString([], {
            month: 'short',
            day: '2-digit',
            hour: '2-digit',
            minute: '2-digit',
          });
        }
        const created = await sessionService.create(title);
        activeSessionId = created.sessionId;
        addSession(created);
        dispatch({ type: 'SET_SESSION', payload: { sessionId: activeSessionId } });
        navigate(`/chat/${activeSessionId}`, { replace: true });
      } catch (err) { showToast(err.message || 'Failed to create session', 'error'); setInput(query); return; }
    }
    if (query) {
      const userMessage = { id: crypto.randomUUID(), role: 'USER', text: query, createdAt: new Date().toISOString(), status: 'complete' };
      const assistantPlaceholder = { id: crypto.randomUUID(), role: 'ASSISTANT', text: '', createdAt: new Date().toISOString(), status: 'streaming' };
      dispatch({ type: 'SEND_MESSAGE_OPTIMISTIC', sessionId: activeSessionId, payload: { userMessage, assistantPlaceholder } });
      
      try {
        if (hasPendingFiles) await uploadPendingFiles(activeSessionId);
        
        await chatService.streamMessage(
          activeSessionId, query, selectedModel,
          (chunk) => dispatch({ type: 'APPEND_STREAM_CHUNK', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id, chunk } }),
          (citations) => dispatch({ type: 'SET_CITATIONS', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id, citations } }),
          (err)   => { dispatch({ type: 'STREAM_ERROR', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } }); showToast(err.message || 'Stream error', 'error'); },
          ()      => {
            dispatch({ type: 'STREAM_DONE', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } });
            // Backend regenerates suggested questions after every response too
            // (not just after uploads) — poll the same endpoint to pick them up.
            pollSuggestedQuestions(activeSessionId);
          },
        );
      } catch (err) {
        dispatch({ type: 'STREAM_ERROR', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } });
        showToast(err.message || 'Failed to send', 'error');
      }
    } else {
      if (hasPendingFiles) await uploadPendingFiles(activeSessionId);
    }
  }, [input, pendingFiles, state.sessionId, state.isStreaming, navigate, addSession, showToast, uploadPendingFiles, selectedModel, pollSuggestedQuestions]);

  const handleWikiSubmit = async (e) => {
    if (e) e.preventDefault();
    if (!wikiUrl.trim() || !state.sessionId) return;

    const tempId = Date.now();
    let url = wikiUrl.trim();
    if (!url.startsWith('http')) {
      url = 'https://en.wikipedia.org/wiki/' + url.replace(/ /g, '_');
    }
    
    setPendingFiles(prev => [...prev, { name: decodeURIComponent(url.substring(url.lastIndexOf('/') + 1)), id: tempId, status: 'uploading' }]);
    
    setShowWikiInput(false);
    setWikiUrl('');

    try {
      await attachmentService.uploadWikipedia(state.sessionId, url);
      setPendingFiles(prev => prev.map(f => f.id === tempId ? { ...f, status: 'done' } : f));
      setTimeout(() => {
        setPendingFiles(prev => prev.filter(f => f.id !== tempId));
      }, 2000);
      pollSuggestedQuestions(state.sessionId);
    } catch (err) {
      setPendingFiles(prev => prev.map(f => f.id === tempId ? { ...f, status: 'error' } : f));
      showToast(err.message, 'error');
    }
  };

  const onKeyDown     = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(e); } };
  const onInputChange = (e) => { setInput(e.target.value); const ta = e.target; ta.style.height = 'auto'; ta.style.height = Math.min(ta.scrollHeight, 160) + 'px'; };

  const firstName = user?.name?.split(' ')[0] || 'there';
  const isNewChat = !state.sessionId;

  const greetings = [
    `Hello, ${firstName}! How can I help you today?`,
    `Hey ${firstName}, what do you have in mind?`,
    `Welcome back, ${firstName}! Ready to dive in?`,
    `Hi ${firstName}, what are we working on today?`,
    `Good to see you, ${firstName}! How can I assist?`,
    `Hey there, ${firstName}! What's the plan for today?`,
    `Greetings, ${firstName}! What can I help you discover?`,
    `Hi ${firstName}! Let's get started. What's on your mind?`,
    `Hello ${firstName}, need any help with your projects?`,
    `Hey ${firstName}! What shall we explore today?`,
    `Hi ${firstName}, how can I make your day easier?`,
    `Welcome, ${firstName}! What are we tackling next?`
  ];

  return (
    <div
      className="flex-1 flex flex-col h-full overflow-hidden relative"
      style={{ backgroundColor: 'var(--color-bg-base)' }}
      onDragEnter={onDragEnter}
      onDragLeave={onDragLeave}
      onDragOver={onDragOver}
      onDrop={onDrop}
    >
      {isDragging && (
        <div className="absolute inset-0 z-50 flex flex-col items-center justify-center backdrop-blur-sm border-2 border-dashed border-blue-500/60 pointer-events-none"
          style={{ backgroundColor: 'color-mix(in srgb, var(--color-bg-base) 85%, transparent)' }}>
          <svg className="w-12 h-12 text-blue-400 mb-3 animate-bounce" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
          </svg>
          <p className="text-blue-500 text-base font-semibold">Drop files to upload</p>
          <p className="text-secondary text-xs mt-1">PDF, images, text, markdown…</p>
        </div>
      )}

      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => { if (e.target.files.length) addFiles(Array.from(e.target.files)); e.target.value = ''; }}
      />

      <header className="flex items-center gap-3 h-14 px-4 shrink-0 z-30 relative divider-vert"
        style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>
        <button
          className="md:hidden p-2 -ml-1 text-secondary rounded-xl interactive"
          onClick={openMobileSidebar}
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>

          <div className="flex-1 flex items-center justify-between">
            <div className="relative">
              <button
                type="button"
                onClick={() => setModelMenuOpen(prev => !prev)}
                className="flex items-center gap-1.5 px-3 py-1.5 text-[15px] font-semibold text-primary rounded-xl interactive cursor-pointer select-none"
              >
                DocuMind <span className="text-tertiary font-medium">{displayModelName}</span>
                <svg className={`w-3.5 h-3.5 text-tertiary transition-transform duration-200 ${modelMenuOpen ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
                </svg>
              </button>

              {modelMenuOpen && (
                <>
                  <div className="fixed inset-0 z-40" onClick={() => setModelMenuOpen(false)} />
                  <div className="absolute left-0 mt-2 z-50 w-[280px] menu-popup py-1.5 animate-fade-in-up">
                    {models.map(m => {
                      const isSelected = m.id === selectedModel;
                      return (
                        <button
                          key={m.id}
                          type="button"
                          onClick={() => handleModelSelect(m.id)}
                          className="flex items-start w-full text-left px-4 py-2.5 interactive group cursor-pointer"
                        >
                          <div className="w-5 shrink-0 pt-0.5">
                            {isSelected && (
                              <svg className="w-3.5 h-3.5 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="3">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M4.5 12.75l6 6 9-13.5" />
                              </svg>
                            )}
                          </div>
                          <div className="flex-1 min-w-0 pr-2">
                            <div className="flex items-center justify-between gap-2">
                              <span className="text-[13px] font-semibold text-primary group-hover:text-blue-500 transition-colors">
                                {m.name}
                              </span>
                              {m.isNew && (
                                <span className="px-2 py-0.5 text-[9px] font-medium bg-blue-500/10 text-blue-500 rounded-full shrink-0">New</span>
                              )}
                            </div>
                            <p className="text-[11px] text-secondary mt-0.5 leading-normal">{m.description}</p>
                          </div>
                        </button>
                      );
                    })}
                  </div>
                </>
              )}
            </div>
            
            {!isNewChat && (
              <div className="hidden sm:block px-3 py-1.5 bg-[var(--color-bg-base)] border border-[var(--color-border)] rounded-lg max-w-[300px]">
                <h1 className="text-[13px] font-medium text-secondary truncate">
                  {session?.title || 'Loading…'}
                </h1>
              </div>
            )}
          </div>
      </header>

      <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6" style={{ backgroundColor: 'var(--color-bg-base)' }}>
        {isNewChat ? (
          <div className="h-full flex flex-col items-center justify-center text-center px-4">
            {user?.profileImageUrl ? (
              <img 
                src={user.profileImageUrl} 
                alt="Profile" 
                className="w-16 h-16 rounded-full object-cover shadow-sm ring-2 ring-white/10"
              />
            ) : (
              <div className="w-16 h-16 rounded-full bg-blue-500/10 flex items-center justify-center text-blue-500 text-2xl font-bold shadow-sm ring-2 ring-white/10">
                {user?.name?.charAt(0)?.toUpperCase() || 'U'}
              </div>
            )}
            <h2 className="mt-4 text-2xl font-bold text-primary max-w-lg leading-tight">
              {greetings[greetingIndex]}
            </h2>
            <p className="mt-2 text-[13px] text-tertiary max-w-xs leading-relaxed">
              Your AI-powered document assistant. Type a message below to get started.
            </p>
          </div>
        ) : state.messagesLoading ? (
          <div className="h-full flex items-center justify-center">
            <div className="flex flex-col items-center gap-3 text-secondary">
              <div className="w-5 h-5 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
              <span className="text-xs">Loading messages…</span>
            </div>
          </div>
        ) : (
          <div className="max-w-2xl mx-auto space-y-5">
            {state.messages.filter(msg => !isUploadAnchorMessage(msg)).map((msg) => {
              const isBot = msg.role === 'ASSISTANT';
              return (
                <div key={msg.id} className={`flex items-start gap-2.5 ${isBot ? '' : 'flex-row-reverse'}`}>
                  {isBot && <BotAvatar />}
                  <div className={`flex flex-col gap-1 max-w-[85%] ${isBot ? '' : 'items-end'}`}>
                    <div className={`px-4 py-3 rounded-2xl text-[13px] leading-relaxed ${
                      isBot
                        ? 'bg-surface border-t rounded-tl-sm text-primary'
                        : 'bg-blue-600 text-white rounded-tr-sm'
                    } ${msg.status === 'error' ? 'border-red-500/40' : ''}`}
                    style={isBot ? {
                      backgroundColor: 'var(--color-bg-surface)',
                      border: `1px solid var(--color-border)`,
                      boxShadow: 'var(--shadow-sm)',
                    } : {}}>
                      {isBot
                        ? <Streaming 
                            text={msg.text} 
                            isStreaming={msg.id === state.streamingMessageId}
                            citations={msg.citations}
                            onCitationClick={setActiveCitation}
                          />
                        : <p className="whitespace-pre-wrap">{msg.text}</p>
                      }
                      {msg.citations && msg.citations.length > 0 && (() => {
                        const grouped = msg.citations.reduce((acc, cite) => {
                          const existing = acc.find(c => c.sourceName === cite.sourceName);
                          if (existing) {
                            existing.count = (existing.count || 1) + 1;
                          } else {
                            acc.push({ ...cite, count: 1 });
                          }
                          return acc;
                        }, []);
                        return (
                          <div className="mt-3 pt-3" style={{ borderTop: '1px solid var(--color-border)' }}>
                            <div className="flex flex-wrap gap-2">
                              {grouped.map((group, idx) => (
                                <button
                                  key={idx}
                                  onClick={() => setActiveCitation(group)}
                                  title={group.excerpt}
                                  className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl border interactive cursor-pointer"
                                  style={{ backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)' }}
                                >
                                  {group.isImage ? (
                                    <svg className="w-3.5 h-3.5 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14M14 8h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                                    </svg>
                                  ) : (
                                    <svg className="w-3.5 h-3.5 text-blue-500 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                                    </svg>
                                  )}
                                  <span className="text-[12px] font-medium truncate max-w-[160px]" style={{ color: 'var(--color-text-primary)' }}>
                                    {group.sourceName}
                                  </span>
                                  {group.count > 1 && (
                                    <span className="ml-1 text-[10px] font-bold text-blue-600 dark:text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded-md">
                                      ×{group.count}
                                    </span>
                                  )}
                                </button>
                              ))}
                            </div>
                          </div>
                        );
                      })()}
                    </div>
                    {msg.status === 'error' && (
                      <span className="text-[11px] text-red-400">Failed to send</span>
                    )}
                  </div>
                </div>
              );
            })}
            {state.suggestedQuestions.length > 0 && !state.isStreaming && (
              <div className="flex items-start gap-2.5">
                <div className="w-7 shrink-0" />
                <div className="flex flex-col gap-2 max-w-[85%] animate-fade-in-up">
                  <span className="text-[11px] font-medium text-tertiary px-1">You might want to ask</span>
                  <div className="flex flex-wrap gap-2">
                    {state.suggestedQuestions.map((q, i) => (
                      <button
                        key={i}
                        type="button"
                        onClick={() => handleSend(null, q)}
                        className="px-3 py-2 rounded-xl border text-[12.5px] font-medium text-left interactive cursor-pointer transition-all hover:border-blue-500/50 hover:text-blue-500"
                        style={{ backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)', color: 'var(--color-text-primary)' }}
                      >
                        {q}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>
        )}
      </div>
      <div className="shrink-0 px-4 sm:px-6 pb-5 pt-3"
        style={{ borderTop: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>
        <form onSubmit={handleSend} className="max-w-2xl mx-auto">
          {pendingFiles.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mb-2 px-1">
              {pendingFiles.map((f, i) => (
                <div
                  key={i}
                  className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium border transition-all ${
                    f.status === 'uploading' ? 'bg-blue-500/10 border-blue-500/30 text-blue-500' :
                    f.status === 'done'      ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-600' :
                    f.status === 'error'     ? 'bg-red-500/10 border-red-500/30 text-red-500' :
                                              'bg-subtle border-t text-secondary'
                  }`}
                  style={['pending'].includes(f.status) ? { backgroundColor: 'var(--color-bg-subtle)', borderColor: 'var(--color-border)' } : {}}
                >
                  {f.status === 'uploading' && <div className="w-2.5 h-2.5 border border-blue-400/50 border-t-blue-400 rounded-full animate-spin" />}
                  {f.status === 'done'      && <span>✓</span>}
                  {f.status === 'error'     && <span>✗</span>}
                  <span className="max-w-[120px] truncate">{f.name}</span>
                  {(f.status === 'pending' || f.status === 'error') && (
                    <button type="button" onClick={() => removePendingFile(i)} className="text-tertiary hover:text-secondary transition-colors ml-0.5">
                      <XIcon />
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}

          {showWikiInput && (
            <div className="mb-2 p-3 rounded-2xl bg-surface border shadow-lg flex items-center gap-2 animate-fade-in-up" style={{ borderColor: 'var(--color-border)' }}>
              <input
                type="text"
                value={wikiUrl}
                onChange={(e) => setWikiUrl(e.target.value)}
                placeholder="Paste Wikipedia URL or Title..."
                className="flex-1 bg-transparent border-0 text-[13px] text-primary outline-none px-2"
                autoFocus
                onKeyDown={(e) => {
                  if (e.key === 'Enter') handleWikiSubmit(e);
                  if (e.key === 'Escape') setShowWikiInput(false);
                }}
              />
              <button
                type="button"
                onClick={handleWikiSubmit}
                className="bg-blue-600 hover:bg-blue-700 text-white px-3 py-1.5 rounded-lg text-xs font-medium transition-colors"
              >
                Add
              </button>
            </div>
          )}

          <div
            className="flex items-end gap-2.5 rounded-2xl px-4 py-3 transition-all"
            style={{
              backgroundColor: 'var(--color-bg-input)',
              border: '1px solid var(--color-border)',
            }}
            onFocus={(e) => e.currentTarget.style.borderColor = 'var(--color-accent)'}
            onBlur={(e) => e.currentTarget.style.borderColor  = 'var(--color-border)'}
          >
            <div className="flex items-center">
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="p-1.5 text-tertiary hover:text-secondary rounded-lg interactive shrink-0"
                title="Attach file"
              >
                <PaperclipIcon />
              </button>
              <button
                type="button"
                onClick={() => setShowWikiInput(!showWikiInput)}
                className={`p-1.5 rounded-lg interactive shrink-0 transition-colors ${showWikiInput ? 'text-blue-500 bg-blue-500/10' : 'text-tertiary hover:text-secondary'}`}
                title="Attach Wikipedia page"
              >
                <LinkIcon />
              </button>
            </div>

            <textarea
              ref={textareaRef}
              value={input}
              onChange={onInputChange}
              onKeyDown={onKeyDown}
              placeholder="Ask anything…"
              rows={1}
              disabled={state.isStreaming || state.messagesLoading}
              autoFocus
              className="flex-1 bg-transparent border-0 text-[13px] text-primary outline-none resize-none max-h-40 min-h-[22px] py-0.5 leading-relaxed disabled:opacity-50"
              style={{ height: '22px', color: 'var(--color-text-primary)', caretColor: 'var(--color-accent)' }}
            />

            <button
              type="submit"
              disabled={(!input.trim() && !pendingFiles.some(f => f.status === 'pending')) || state.isStreaming || state.messagesLoading}
              className="p-2 bg-blue-600 hover:bg-blue-500 disabled:opacity-25 disabled:cursor-not-allowed text-white rounded-xl transition-all active:scale-95 hover:scale-105 disabled:scale-100 shrink-0 cursor-pointer"
            >
              {state.isStreaming
                ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                : <SendIcon />
              }
            </button>
          </div>

          <p className="text-center text-[11px] text-tertiary mt-2 select-none">
            Enter to send &nbsp;·&nbsp; Shift+Enter for new line &nbsp;·&nbsp; Drop files anywhere
          </p>
        </form>
      </div>

      <CitationDrawer citation={activeCitation} onClose={() => setActiveCitation(null)} />
    </div>
  );
}