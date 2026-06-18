import { useEffect, useState } from 'react';
import { useParams, useNavigate, useOutletContext } from 'react-router-dom';
import { attachmentService } from '../services/attachmentService.js';
import { useSessions } from '../context/SessionsContext.jsx';

// ── Icons ─────────────────────────────────────────────────────────────────────

const ArrowLeftIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" />
  </svg>
);

const MenuIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
  </svg>
);

const FILE_ICONS = {
  PDF: (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
      <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
    </svg>
  ),
  IMAGE: (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
      <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909m-18 3.75h16.5a1.5 1.5 0 001.5-1.5V6a1.5 1.5 0 00-1.5-1.5H3.75A1.5 1.5 0 002.25 6v12a1.5 1.5 0 001.5 1.5zm10.5-11.25h.008v.008h-.008V8.25zm.375 0a.375.375 0 11-.75 0 .375.375 0 01.75 0z" />
    </svg>
  ),
  TEXT: (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
      <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m2.25 0H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
    </svg>
  ),
  OTHER: (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
      <path strokeLinecap="round" strokeLinejoin="round" d="M18.375 12.739l-7.693 7.693a4.5 4.5 0 01-6.364-6.364l10.94-10.94A3 3 0 1119.5 7.372L8.552 18.32m.009-.01l-.01.01m5.699-9.941l-7.81 7.81a1.5 1.5 0 002.112 2.13" />
    </svg>
  ),
};

const TYPE_COLORS = {
  PDF:   { bg: 'bg-red-500/10',    border: 'border-red-500/20',    text: 'text-red-400',    dot: 'bg-red-400' },
  IMAGE: { bg: 'bg-violet-500/10', border: 'border-violet-500/20', text: 'text-violet-400', dot: 'bg-violet-400' },
  TEXT:  { bg: 'bg-sky-500/10',    border: 'border-sky-500/20',    text: 'text-sky-400',    dot: 'bg-sky-400' },
  OTHER: { bg: 'bg-slate-500/10',  border: 'border-slate-500/20',  text: 'text-slate-400',  dot: 'bg-slate-400' },
};

const formatBytes = (bytes) => {
  if (!bytes || bytes < 0) return '—';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

const formatDate = (iso) => {
  if (!iso) return '—';
  return new Date(iso).toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
};

// ── Component ─────────────────────────────────────────────────────────────────

export default function Attachments() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const { openMobileSidebar } = useOutletContext();
  const { sessions } = useSessions();

  const [attachments, setAttachments] = useState([]);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState(null);
  const [filter, setFilter]           = useState('ALL'); // ALL | PDF | IMAGE | TEXT | OTHER

  const session = sessions.find(s => String(s.sessionId) === String(sessionId));

  useEffect(() => {
    setLoading(true);
    setError(null);
    attachmentService.getBySession(sessionId)
      .then(data => { setAttachments(data || []); setLoading(false); })
      .catch(err => { setError(err.message || 'Failed to load attachments'); setLoading(false); });
  }, [sessionId]);

  const displayed = filter === 'ALL' ? attachments : attachments.filter(a => a.type === filter);

  const typeCounts = attachments.reduce((acc, a) => {
    acc[a.type] = (acc[a.type] || 0) + 1;
    return acc;
  }, {});

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden" style={{ backgroundColor: 'var(--color-bg-base)' }}>
      {/* Header */}
      <header className="flex items-center gap-3 h-14 px-4 shrink-0"
        style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>
        <button
          className="md:hidden p-2 -ml-1 text-secondary rounded-xl interactive"
          onClick={openMobileSidebar}
        >
          <MenuIcon />
        </button>
        <button
          onClick={() => navigate(`/chat/${sessionId}`)}
          className="p-1.5 text-secondary rounded-xl interactive"
          title="Back to chat"
        >
          <ArrowLeftIcon />
        </button>
        <div className="flex-1 min-w-0">
          <h1 className="text-[13px] font-medium text-primary truncate">Attachments</h1>
          {session?.title && (
            <p className="text-[11px] text-tertiary truncate">{session.title}</p>
          )}
        </div>
        <span className="text-[11px] text-tertiary shrink-0">{attachments.length} file{attachments.length !== 1 ? 's' : ''}</span>
      </header>

      {/* Body */}
      <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6">
        <div className="max-w-3xl mx-auto">

          {/* Filter tabs */}
          {!loading && !error && attachments.length > 0 && (
            <div className="flex items-center gap-1.5 mb-6 flex-wrap">
              {['ALL', 'PDF', 'IMAGE', 'TEXT', 'OTHER'].map(type => {
                const count = type === 'ALL' ? attachments.length : (typeCounts[type] || 0);
                if (type !== 'ALL' && !count) return null;
                const colors = type !== 'ALL' ? TYPE_COLORS[type] : null;
                return (
                  <button
                    key={type}
                    onClick={() => setFilter(type)}
                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[11px] font-medium border transition-all ${
                      filter === type
                        ? type === 'ALL'
                          ? 'bg-blue-600 border-blue-600 text-white'
                          : `${colors.bg} ${colors.border} ${colors.text}`
                        : 'border-t text-tertiary interactive'
                    }`}
                  >
                    {type !== 'ALL' && (
                      <span className={`w-1.5 h-1.5 rounded-full ${filter === type ? colors.dot : 'bg-slate-400/30'}`} />
                    )}
                    {type === 'ALL' ? 'All' : type.charAt(0) + type.slice(1).toLowerCase()}
                    <span className={filter === type && type !== 'ALL' ? colors.text : 'text-tertiary'}>
                      {count}
                    </span>
                  </button>
                );
              })}
            </div>
          )}

          {/* States */}
          {loading && (
            <div className="flex flex-col items-center justify-center py-24 gap-3 text-secondary">
              <div className="w-6 h-6 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
              <span className="text-sm">Loading attachments…</span>
            </div>
          )}

          {error && !loading && (
            <div className="flex flex-col items-center justify-center py-24 gap-3 text-slate-600">
              <svg className="w-10 h-10 text-red-500/40" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
              </svg>
              <p className="text-sm text-red-400">{error}</p>
            </div>
          )}

          {!loading && !error && attachments.length === 0 && (
            <div className="flex flex-col items-center justify-center py-24 gap-4 text-secondary">
              <div className="w-16 h-16 rounded-2xl bg-subtle border-t flex items-center justify-center">
                <svg className="w-7 h-7 text-tertiary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M18.375 12.739l-7.693 7.693a4.5 4.5 0 01-6.364-6.364l10.94-10.94A3 3 0 1119.5 7.372L8.552 18.32m.009-.01l-.01.01m5.699-9.941l-7.81 7.81a1.5 1.5 0 002.112 2.13" />
                </svg>
              </div>
              <div className="text-center">
                <p className="text-sm font-medium text-secondary">No attachments yet</p>
                <p className="text-xs text-tertiary mt-1">Upload files in the chat to see them here.</p>
              </div>
              <button
                onClick={() => navigate(`/chat/${sessionId}`)}
                className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium rounded-xl transition-colors"
              >
                Go to chat
              </button>
            </div>
          )}

          {/* Grid */}
          {!loading && !error && displayed.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {displayed.map(att => {
                const colors  = TYPE_COLORS[att.type] || TYPE_COLORS.OTHER;
                const icon    = FILE_ICONS[att.type]  || FILE_ICONS.OTHER;
                const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
                const fileUrl = att.url ? (att.url.startsWith('http') ? att.url : `${API_URL}${att.url}`) : null;
                const Tag     = fileUrl ? 'a' : 'div';
                const tagProps = fileUrl
                  ? { href: fileUrl, target: '_blank', rel: 'noopener noreferrer', title: `Open ${att.fileName}` }
                  : {};
                return (
                  <Tag
                    key={att.attachmentId}
                    {...tagProps}
                    className={`group flex items-start gap-3.5 p-4 rounded-2xl border transition-all ${
                      fileUrl
                        ? 'hover:scale-[1.015] cursor-pointer'
                        : 'cursor-default'
                    } ${colors.bg} ${colors.border}`}
                  >
                    {/* Icon */}
                    <div className={`shrink-0 w-10 h-10 rounded-xl flex items-center justify-center ${colors.text} bg-subtle border-t`}>
                      {icon}
                    </div>

                    {/* Info */}
                    <div className="flex-1 min-w-0">
                      <p className="text-[13px] font-medium text-primary truncate" title={att.fileName}>
                        {att.fileName || 'Unnamed file'}
                      </p>
                      <p className="text-[11px] text-tertiary mt-0.5 truncate" title={att.storagePath}>
                        {att.storagePath}
                      </p>
                      <div className="flex items-center gap-3 mt-2">
                        <span className={`inline-flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider ${colors.text}`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${colors.dot}`} />
                          {att.type}
                        </span>
                        <span className="text-[11px] text-tertiary">{formatBytes(att.fileSizeBytes)}</span>
                      </div>
                    </div>

                    {/* Date */}
                    <div className="shrink-0 text-right">
                      <p className="text-[10px] text-tertiary leading-tight">{formatDate(att.uploadedAt).split(',')[0]}</p>
                      <p className="text-[10px] text-tertiary leading-tight">{formatDate(att.uploadedAt).split(',')[1]?.trim()}</p>
                    </div>
                  </Tag>
                );
              })}
            </div>
          )}

          {/* Empty filter state */}
          {!loading && !error && attachments.length > 0 && displayed.length === 0 && (
            <div className="flex flex-col items-center justify-center py-20 gap-3 text-secondary">
              <p className="text-sm">No {filter.toLowerCase()} files in this session.</p>
              <button onClick={() => setFilter('ALL')} className="text-xs text-blue-500 hover:text-blue-400">
                Clear filter
              </button>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
