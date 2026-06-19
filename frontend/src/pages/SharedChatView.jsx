import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { sessionService } from '../services/sessionService.js';
import Streaming from '../components/streaming.jsx';

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

export default function SharedChatView() {
  const { uuid } = useParams();
  const navigate = useNavigate();
  const [session, setSession] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    sessionService.getSharedSession(uuid)
      .then(data => {
        if (!cancelled) {
          setSession(data);
          setLoading(false);
        }
      })
      .catch(err => {
        if (!cancelled) {
          setError(err.message || 'Failed to load shared session');
          setLoading(false);
        }
      });

    return () => { cancelled = true; };
  }, [uuid]);

  if (loading) {
    return (
      <div className="flex-1 h-screen flex items-center justify-center" style={{ backgroundColor: 'var(--color-bg-base)' }}>
        <div className="flex flex-col items-center gap-3 text-secondary">
          <div className="w-5 h-5 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
          <span className="text-xs">Loading shared conversation…</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex-1 h-screen flex flex-col items-center justify-center px-4" style={{ backgroundColor: 'var(--color-bg-base)' }}>
        <div className="w-12 h-12 rounded-full bg-red-500/10 flex items-center justify-center text-red-500 mb-4">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        </div>
        <h2 className="text-lg font-bold" style={{ color: 'var(--color-text-primary)' }}>Failed to load chat</h2>
        <p className="text-sm mt-1 text-center max-w-sm" style={{ color: 'var(--color-text-secondary)' }}>{error}</p>
        <button
          onClick={() => navigate('/')}
          className="mt-6 px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-lg text-sm font-semibold transition-colors cursor-pointer"
        >
          Go to home page
        </button>
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col h-screen overflow-hidden" style={{ backgroundColor: 'var(--color-bg-base)' }}>
      {/* Header */}
      <header className="flex items-center justify-between h-14 px-6 shrink-0 z-30 relative border-b"
        style={{ borderColor: 'var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>
        <div className="flex items-center gap-2">
          <BotAvatar />
          <span className="text-sm font-bold text-primary">DocuMind</span>
          <span className="px-2 py-0.5 text-[10px] font-semibold bg-blue-500/10 text-blue-500 rounded-full shrink-0">Shared Chat</span>
        </div>
        <button
          onClick={() => navigate('/')}
          className="px-3.5 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded-xl text-xs font-semibold transition-colors cursor-pointer"
        >
          Start new chat
        </button>
      </header>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-8">
        <div className="max-w-2xl mx-auto mb-10 text-center">
          <span className="text-xs uppercase tracking-wider font-semibold text-tertiary">Shared Conversation</span>
          <h1 className="text-2xl font-bold mt-2" style={{ color: 'var(--color-text-primary)' }}>{session?.title}</h1>
          <p className="text-xs mt-1.5" style={{ color: 'var(--color-text-tertiary)' }}>
            Shared on {new Date(session?.createdAt).toLocaleString()}
          </p>
        </div>

        <div className="max-w-2xl mx-auto space-y-6">
          {session?.messages?.map((msg) => {
            const isBot = msg.role === 'ASSISTANT';
            return (
              <div key={msg.id} className={`flex items-start gap-2.5 ${isBot ? '' : 'flex-row-reverse'}`}>
                {isBot && <BotAvatar />}
                <div className={`flex flex-col gap-1 max-w-[85%] ${isBot ? '' : 'items-end'}`}>
                  <div className={`px-4 py-3 rounded-2xl text-[13px] leading-relaxed ${
                    isBot
                      ? 'bg-surface border-t rounded-tl-sm text-primary'
                      : 'bg-blue-600 text-white rounded-tr-sm'
                  }`}
                  style={isBot ? {
                    backgroundColor: 'var(--color-bg-surface)',
                    border: `1px solid var(--color-border)`,
                    boxShadow: 'var(--shadow-sm)',
                  } : {}}>
                    {isBot
                      ? <Streaming text={msg.text} isStreaming={false} />
                      : <p className="whitespace-pre-wrap">{msg.text}</p>
                    }
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
