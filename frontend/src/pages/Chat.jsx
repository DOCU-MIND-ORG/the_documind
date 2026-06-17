import React, { useReducer, useRef, useEffect, useCallback } from 'react';
import { useParams, useNavigate, useOutletContext } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { chatService } from '../services/chatService.js';
import { useToast } from '../context/ToastContext.jsx';
import Streaming from '../components/streaming.jsx';
import { chatReducer, initialChatState } from '../state/chatReducer.js';

// ─── Icons (unchanged) ────────────────────────────────────────────────────

const SendIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
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

  const [input, setInput] = React.useState('');
  const bottomRef    = useRef(null);
  const textareaRef  = useRef(null);

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

  // ── The single send path, used identically whether or not a session
  //    exists yet. This is the fix for the original bug. ──
  const handleSend = useCallback(async (e) => {
    if (e) e.preventDefault();
    const query = input.trim();
    if (!query || state.isStreaming) return;

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
        // Update the URL to reflect the new session, WITHOUT a state payload
        // and without remounting this component (replace, not push+remount).
        navigate(`/chat/${activeSessionId}`, { replace: true });
      } catch (err) {
        showToast(err.message || 'Failed to create session', 'error');
        setInput(query); // restore what they typed so it isn't lost
        return;
      }
    }

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
  }, [input, state.sessionId, state.isStreaming, navigate, addSession, showToast]);

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

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden">
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
            <h2 className="mt-4 text-2xl font-bold text-white">Hello, {firstName} 👋</h2>
            <p className="mt-1.5 text-[13px] text-slate-500 max-w-xs leading-relaxed">
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
          <div className="flex items-end gap-2.5 bg-[#1a1e26] border border-white/[0.08] rounded-2xl px-4 py-3 transition-all focus-within:border-blue-500/40 focus-within:shadow-[0_0_0_1px_rgba(59,130,246,0.15)]">
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
              disabled={!input.trim() || state.isStreaming || state.messagesLoading}
              className="p-2 bg-blue-600 hover:bg-blue-500 disabled:opacity-25 disabled:cursor-not-allowed text-white rounded-xl transition-all active:scale-95 hover:scale-105 disabled:scale-100 shrink-0 cursor-pointer"
            >
              {state.isStreaming
                ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                : <SendIcon />
              }
            </button>
          </div>
          <p className="text-center text-[11px] text-slate-700 mt-2 select-none">
            Enter to send &nbsp;·&nbsp; Shift+Enter for new line
          </p>
        </form>
      </div>
    </div>
  );
}