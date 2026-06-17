import React, { useReducer, useRef, useEffect, useCallback, useState } from 'react';
import { useParams, useNavigate, useOutletContext } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { chatService } from '../services/chatService.js';
import { attachmentService } from '../services/attachmentService.js';
import { useToast } from '../context/ToastContext.jsx';
import Streaming from '../components/streaming.jsx';
import { chatReducer, initialChatState } from '../state/chatReducer.js';

// ─── Icons (unchanged) ────────────────────────────────────────────────────

const SendIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
  </svg>
);

const PaperclipIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
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

/** Format bytes to human readable string */
const formatBytes = (bytes) => {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

// ─── Component ───────────────────────────────────────────────────────────
//
// This single component renders BOTH the "new chat" landing state and an
// active conversation. There is no separate Dashboard.jsx for composing the
// first message — that split is exactly what caused the lost-message bug,
// because it required smuggling the typed text across a navigation via
// location.state. Here, the textarea/send handler never change identity
// across the "no session yet" -> "session exists" transition, so there's
// nothing to lose.

export default function Chat() {
  const { sessionId: routeSessionId } = useParams(); // undefined on "/"
  const navigate                      = useNavigate();
  const { user }                      = useAuth();
  const { sessions, addSession }      = useSessions();
  const { showToast }                 = useToast();
  const { openMobileSidebar }         = useOutletContext();

  const [state, dispatch] = useReducer(chatReducer, {
    ...initialChatState,
    sessionId: routeSessionId ?? null,
  });

  const [input, setInput] = useState('');
  const [isDragging, setIsDragging]     = useState(false);
  const [pendingFiles, setPendingFiles] = useState([]); // { file, status: 'pending'|'uploading'|'done'|'error', name }
  const bottomRef   = useRef(null);
  const textareaRef = useRef(null);
  const fileInputRef = useRef(null);
  const dragCounterRef = useRef(0); // track nested dragenter/dragleave

  const [greetingIndex] = useState(() => Math.floor(Math.random() * 12));

  const session = sessions.find(s => String(s.sessionId) === String(state.sessionId));

  // ── Sync reducer's sessionId when the URL param changes (back/forward,
  //    sidebar click) — this is a ONE-WAY sync: URL -> state. Sending a
  //    message never relies on this running first; see handleSend below. ──
  useEffect(() => {
    dispatch({ type: 'SYNC_ROUTE_SESSION', payload: { sessionId: routeSessionId ?? null } });
  }, [routeSessionId]);

  // ── Load message history whenever we land on an existing session ──
  useEffect(() => {
    if (!state.sessionId) return; // new-chat screen, nothing to load
    
    // Prevent fetching if we just created the session and populated it optimistically
    if (state.messages.length > 0) return;

    let cancelled = false;
    dispatch({ type: 'MESSAGES_LOADING', sessionId: state.sessionId });

    sessionService.getMessages(state.sessionId)
      .then(msgs => {
        if (cancelled) return;
        dispatch({ type: 'MESSAGES_LOADED', sessionId: state.sessionId, payload: { messages: msgs } });
      })
      .catch(err => {
        if (cancelled) return;
        dispatch({ type: 'MESSAGES_LOAD_FAILED', sessionId: state.sessionId });
        showToast(err.message || 'Failed to load messages', 'error');
      });

    return () => { cancelled = true; };
  }, [state.sessionId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [state.messages]);

  // ── Drag and drop handlers ────────────────────────────────────────────────
  const onDragEnter = useCallback((e) => {
    e.preventDefault();
    dragCounterRef.current += 1;
    if (dragCounterRef.current === 1) setIsDragging(true);
  }, []);

  const onDragLeave = useCallback((e) => {
    e.preventDefault();
    dragCounterRef.current -= 1;
    if (dragCounterRef.current === 0) setIsDragging(false);
  }, []);

  const onDragOver = useCallback((e) => { e.preventDefault(); }, []);

  const onDrop = useCallback((e) => {
    e.preventDefault();
    dragCounterRef.current = 0;
    setIsDragging(false);
    const files = Array.from(e.dataTransfer.files);
    if (files.length > 0) addFiles(files);
  }, []);

  const addFiles = useCallback((files) => {
    const newEntries = files.map(f => ({ file: f, name: f.name, status: 'pending' }));
    setPendingFiles(prev => [...prev, ...newEntries]);
  }, []);

  const removePendingFile = useCallback((index) => {
    setPendingFiles(prev => prev.filter((_, i) => i !== index));
  }, []);

  // Upload all pending files; requires a valid sessionId
  const uploadPendingFiles = useCallback(async (sessionId) => {
    const toUpload = pendingFiles.filter(f => f.status === 'pending');
    if (toUpload.length === 0) return;

    for (let i = 0; i < pendingFiles.length; i++) {
      if (pendingFiles[i].status !== 'pending') continue;
      setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'uploading' } : f));
      try {
        await attachmentService.upload(sessionId, pendingFiles[i].file);
        setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'done' } : f));
      } catch (err) {
        setPendingFiles(prev => prev.map((f, idx) => idx === i ? { ...f, status: 'error' } : f));
        showToast(`Failed to upload ${pendingFiles[i].name}`, 'error');
      }
    }
    // Clear done files after a short delay
    setTimeout(() => setPendingFiles(prev => prev.filter(f => f.status !== 'done')), 2000);
  }, [pendingFiles, showToast]);

  // ── The single send path, used identically whether or not a session
  //    exists yet. This is the fix for the original bug. ──
  const handleSend = useCallback(async (e) => {
    if (e) e.preventDefault();
    const query = input.trim();
    const hasPendingFiles = pendingFiles.some(f => f.status === 'pending');
    if (!query && !hasPendingFiles) return;
    if (state.isStreaming) return;

    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';

    // 1. Resolve a session id FIRST, synchronously in this same handler —
    //    no navigation round-trip, no location.state, nothing to lose.
    let activeSessionId = state.sessionId;
    if (!activeSessionId) {
      try {
        const title = query.length <= 40 ? query : query.slice(0, 40) + '…';
        const created = await sessionService.create(title);
        activeSessionId = created.sessionId;
        addSession(created);
        dispatch({ type: 'SET_SESSION', payload: { sessionId: activeSessionId } });
        navigate(`/chat/${activeSessionId}`, { replace: true });
      } catch (err) {
        showToast(err.message || 'Failed to create session', 'error');
        setInput(query);
        return;
      }
    }

    // 1b. Upload any pending files immediately after we have a session id
    if (hasPendingFiles) {
      await uploadPendingFiles(activeSessionId);
    }

    // If there's no text query, we're done (files-only upload)
    if (!query) return;

    // 2. Optimistic insert — message renders NOW, independent of the
    //    network call below succeeding or failing.
    const userMessage = {
      id: crypto.randomUUID(),
      role: 'USER',
      text: query,
      createdAt: new Date().toISOString(),
      status: 'complete',
    };
    const assistantPlaceholder = {
      id: crypto.randomUUID(),
      role: 'ASSISTANT',
      text: '',
      createdAt: new Date().toISOString(),
      status: 'streaming',
    };

    dispatch({
      type: 'SEND_MESSAGE_OPTIMISTIC',
      sessionId: activeSessionId,
      payload: { userMessage, assistantPlaceholder },
    });

    // 3. Stream the response. Every dispatch is tagged with activeSessionId,
    //    so if the user navigates away mid-stream, the reducer guard at the
    //    top of chatReducer silently drops these — no cross-session bleed.
    try {
      await chatService.streamMessage(
        activeSessionId,
        query,
        (chunk) => dispatch({
          type: 'APPEND_STREAM_CHUNK',
          sessionId: activeSessionId,
          payload: { messageId: assistantPlaceholder.id, chunk },
        }),
        (err) => {
          dispatch({ type: 'STREAM_ERROR', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } });
          showToast(err.message || 'Stream error', 'error');
        },
        () => dispatch({ type: 'STREAM_DONE', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } }),
      );
    } catch (err) {
      dispatch({ type: 'STREAM_ERROR', sessionId: activeSessionId, payload: { messageId: assistantPlaceholder.id } });
      showToast(err.message || 'Failed to send', 'error');
    }
  }, [input, pendingFiles, state.sessionId, state.isStreaming, navigate, addSession, showToast, uploadPendingFiles]);

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(e); }
  };

  const onInputChange = (e) => {
    setInput(e.target.value);
    const ta = e.target;
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 160) + 'px';
  };

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
      onDragEnter={onDragEnter}
      onDragLeave={onDragLeave}
      onDragOver={onDragOver}
      onDrop={onDrop}
    >
      {/* Drag overlay */}
      {isDragging && (
        <div className="absolute inset-0 z-50 flex flex-col items-center justify-center bg-[#0f1115]/80 backdrop-blur-sm border-2 border-dashed border-blue-500/60 rounded-none pointer-events-none">
          <svg className="w-12 h-12 text-blue-400 mb-3 animate-bounce" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5m-13.5-9L12 3m0 0l4.5 4.5M12 3v13.5" />
          </svg>
          <p className="text-blue-300 text-base font-semibold">Drop files to upload</p>
          <p className="text-slate-500 text-xs mt-1">PDF, images, text, markdown…</p>
        </div>
      )}
      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => { if (e.target.files.length) addFiles(Array.from(e.target.files)); e.target.value = ''; }}
      />
      <header className="flex items-center gap-3 h-14 px-4 border-b border-white/[0.05] shrink-0">
        <button
          className="md:hidden p-2 -ml-1 text-slate-400 hover:text-white rounded-xl hover:bg-white/[0.05] transition-all"
          onClick={openMobileSidebar}
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
            <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
        <h1 className="flex-1 text-[13px] font-medium text-slate-300 truncate">
          {isNewChat ? 'DocuMind' : (session?.title || 'Loading…')}
        </h1>
      </header>

      <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6">
        {isNewChat ? (
          <div className="h-full flex flex-col items-center justify-center text-center px-4">
            <BotAvatar />
            <h2 className="mt-4 text-2xl font-bold text-white max-w-lg leading-tight">
              {greetings[greetingIndex]}
            </h2>
            <p className="mt-2 text-[13px] text-slate-500 max-w-xs leading-relaxed">
              Your AI-powered document assistant. Type a message below to get started.
            </p>
          </div>
        ) : state.messagesLoading ? (
          <div className="h-full flex items-center justify-center">
            <div className="flex flex-col items-center gap-3 text-slate-600">
              <div className="w-5 h-5 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
              <span className="text-xs">Loading messages…</span>
            </div>
          </div>
        ) : (
          <div className="max-w-2xl mx-auto space-y-5">
            {state.messages.map((msg) => {
              const isBot = msg.role === 'ASSISTANT';
              return (
                <div key={msg.id} className={`flex items-start gap-2.5 ${isBot ? '' : 'flex-row-reverse'}`}>
                  <div className={`flex flex-col gap-1 max-w-[85%] ${isBot ? '' : 'items-end'}`}>
                    <div className={`px-4 py-3 rounded-2xl text-[13px] leading-relaxed shadow-sm ${
                      isBot
                        ? 'bg-[#1a1e26] border border-white/[0.06] text-slate-200 rounded-tl-sm'
                        : 'bg-blue-600 text-white rounded-tr-sm'
                    } ${msg.status === 'error' ? 'border border-red-500/40' : ''}`}>
                      {isBot
                        ? <Streaming text={msg.text} isStreaming={msg.id === state.streamingMessageId} />
                        : <p className="whitespace-pre-wrap">{msg.text}</p>
                      }
                    </div>
                    {msg.status === 'error' && (
                      <span className="text-[11px] text-red-400">Failed to send</span>
                    )}
                  </div>
                </div>
              );
            })}
            <div ref={bottomRef} />
          </div>
        )}
      </div>

      <div className="shrink-0 px-4 sm:px-6 pb-5 pt-2 border-t border-white/[0.05] bg-[#0f1115]">
        <form onSubmit={handleSend} className="max-w-2xl mx-auto">

          {/* Pending file pills */}
          {pendingFiles.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mb-2 px-1">
              {pendingFiles.map((f, i) => (
                <div
                  key={i}
                  className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-medium border transition-all ${
                    f.status === 'uploading' ? 'bg-blue-500/10 border-blue-500/30 text-blue-300' :
                    f.status === 'done'      ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-300' :
                    f.status === 'error'     ? 'bg-red-500/10 border-red-500/30 text-red-300' :
                                              'bg-white/[0.05] border-white/[0.1] text-slate-300'
                  }`}
                >
                  {f.status === 'uploading' && <div className="w-2.5 h-2.5 border border-blue-300/50 border-t-blue-300 rounded-full animate-spin" />}
                  {f.status === 'done'      && <span>✓</span>}
                  {f.status === 'error'     && <span>✗</span>}
                  <span className="max-w-[120px] truncate">{f.name}</span>
                  {(f.status === 'pending' || f.status === 'error') && (
                    <button type="button" onClick={() => removePendingFile(i)} className="text-slate-500 hover:text-slate-200 transition-colors ml-0.5">
                      <XIcon />
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}

          <div className="flex items-end gap-2.5 bg-[#1a1e26] border border-white/[0.08] rounded-2xl px-4 py-3 transition-all focus-within:border-blue-500/40 focus-within:shadow-[0_0_0_1px_rgba(59,130,246,0.15)]">
            {/* Paperclip button */}
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              className="p-1.5 text-slate-500 hover:text-slate-200 rounded-lg hover:bg-white/[0.05] transition-all shrink-0"
              title="Attach file"
            >
              <PaperclipIcon />
            </button>

            <textarea
              ref={textareaRef}
              value={input}
              onChange={onInputChange}
              onKeyDown={onKeyDown}
              placeholder="Ask anything…"
              rows={1}
              disabled={state.isStreaming || state.messagesLoading}
              autoFocus
              className="flex-1 bg-transparent border-0 text-[13px] text-slate-100 outline-none placeholder-slate-600 resize-none max-h-40 min-h-[22px] py-0.5 leading-relaxed disabled:opacity-50"
              style={{ height: '22px' }}
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
          <p className="text-center text-[11px] text-slate-700 mt-2 select-none">
            Enter to send &nbsp;·&nbsp; Shift+Enter for new line &nbsp;·&nbsp; Drop files anywhere
          </p>
        </form>
      </div>
    </div>
  );
}