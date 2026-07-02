import { useEffect, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { attachmentService } from '../services/attachmentService.js';
import AccentureLoader from '../components/AccentureLoader.jsx';
import Modal from '../components/Modal.jsx';
import { useToast } from '../context/ToastContext.jsx';

const MenuIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M4 6h16M4 12h16M4 18h16" />
  </svg>
);

const SearchIcon = () => (
  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
  </svg>
);

const CloseIcon = () => (
  <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
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
  WIKIPEDIA: (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.5">
      <path strokeLinecap="round" strokeLinejoin="round" d="M13.19 8.688a4.5 4.5 0 011.242 7.244l-4.5 4.5a4.5 4.5 0 01-6.364-6.364l1.757-1.757m13.35-.622l1.757-1.757a4.5 4.5 0 00-6.364-6.364l-4.5 4.5a4.5 4.5 0 001.242 7.244" />
    </svg>
  ),
};

const TYPE_COLORS = {
  PDF:       { bg: 'bg-red-500/10',     border: 'border-red-500/20',     text: 'text-red-400',     dot: 'bg-red-400' },
  IMAGE:     { bg: 'bg-violet-500/10',  border: 'border-violet-500/20',  text: 'text-violet-400',  dot: 'bg-violet-400' },
  TEXT:      { bg: 'bg-sky-500/10',     border: 'border-sky-500/20',     text: 'text-sky-400',     dot: 'bg-sky-400' },
  WIKIPEDIA: { bg: 'bg-emerald-500/10', border: 'border-emerald-500/20', text: 'text-emerald-400', dot: 'bg-emerald-400' },
  OTHER:     { bg: 'bg-slate-500/10',   border: 'border-slate-500/20',   text: 'text-slate-400',   dot: 'bg-slate-400' },
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

const PAGE_SIZE = 24;

// All filter options — covers every attachment type the backend can store
const ALL_TYPES = ['ALL', 'PDF', 'IMAGE', 'TEXT', 'WIKIPEDIA', 'OTHER'];

export default function Explore() {
  const { openMobileSidebar } = useOutletContext();
  const { showToast } = useToast();
  const [attachments, setAttachments] = useState([]);
  const [loading, setLoading]         = useState(true);
  const [error, setError]             = useState(null);
  const [filter, setFilter]           = useState('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [deleteTarget, setDeleteTarget] = useState(null); // attachment pending delete confirmation
  const [deletingId, setDeletingId]     = useState(null); // attachmentId currently being deleted

  useEffect(() => {
    setLoading(true);
    setError(null);
    // getAllGlobal hits GET /api/explore/attachments — returns every type
    // (PDFs, images, text files, Wikipedia links, other) uploaded by THIS user
    attachmentService.getAllGlobal()
      .then(data => { setAttachments(data || []); setLoading(false); })
      .catch(err => { setError(err.message || 'Failed to load your documents'); setLoading(false); })
  }, []);

  useEffect(() => {
    setVisibleCount(PAGE_SIZE);
  }, [filter, searchQuery]);

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleteTarget(null);
    setDeletingId(target.attachmentId);
    try {
      const result = await attachmentService.deleteExploreAttachment(target.attachmentId);
      setAttachments(prev => prev.filter(a => a.attachmentId !== target.attachmentId));
      showToast(result?.message || 'File removed.', 'success');
    } catch (err) {
      showToast(err.message || 'Failed to delete file.', 'error');
    } finally {
      setDeletingId(null);
    }
  };

  let filtered = filter === 'ALL' ? attachments : attachments.filter(a => a.type === filter);

  if (searchQuery.trim()) {
    const q = searchQuery.toLowerCase();
    filtered = filtered.filter(a =>
      (a.fileName || '').toLowerCase().includes(q) ||
      (a.url || '').toLowerCase().includes(q)
    );
  }

  const displayed = filtered.slice(0, visibleCount);
  const hasMore = filtered.length > displayed.length;

  const typeCounts = attachments.reduce((acc, a) => {
    acc[a.type] = (acc[a.type] || 0) + 1;
    return acc;
  }, {});

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden" style={{ backgroundColor: 'var(--color-bg-base)' }}>
      <header className="flex items-center gap-3 h-14 px-4 shrink-0"
        style={{ borderBottom: '1px solid var(--color-border)', backgroundColor: 'var(--color-bg-surface)' }}>
        <button
          className="md:hidden p-2 -ml-1 text-secondary rounded-xl interactive"
          onClick={openMobileSidebar}
        >
          <MenuIcon />
        </button>
        <div className="flex-1 min-w-0 flex items-center gap-2">
          <h1 className="text-[13px] font-medium text-primary truncate">Explore Documents</h1>
          <span className="text-[11px] px-2 py-0.5 rounded bg-blue-500/10 text-blue-400 border border-blue-500/20 font-medium">My Documents</span>
        </div>
        <span className="text-[11px] text-tertiary shrink-0">{attachments.length} file{attachments.length !== 1 ? 's' : ''} total</span>
      </header>

      <div className="flex-1 overflow-y-auto px-4 sm:px-6 py-6">
        <div className="max-w-4xl mx-auto">

          {!loading && !error && attachments.length > 0 && (
            <div className="flex flex-col sm:flex-row sm:items-center gap-4 mb-6">
              <div className="relative flex-1">
                <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-tertiary">
                  <SearchIcon />
                </div>
                <input
                  type="text"
                  placeholder="Search PDFs, images, links…"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full bg-subtle border t-border rounded-xl pl-10 pr-4 py-2.5 text-[13px] outline-none focus:border-blue-500/40 focus:ring-2 focus:ring-blue-500/10 transition-all placeholder:text-tertiary/70 text-primary"
                />
              </div>

              {/* Filter pills — one for each file type that has at least one file */}
              <div className="flex items-center gap-1.5 flex-wrap">
                {ALL_TYPES.map(type => {
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
                            ? 'bg-blue-600 border-blue-600 text-white shadow-sm'
                            : `${colors.bg} ${colors.border} ${colors.text} shadow-sm`
                          : 'border-t text-tertiary interactive'
                      }`}
                    >
                      {type !== 'ALL' && (
                        <span className={`w-1.5 h-1.5 rounded-full ${filter === type ? colors.dot : 'bg-slate-400/30'}`} />
                      )}
                      {type === 'ALL' ? 'All' : type === 'WIKIPEDIA' ? 'Links' : type.charAt(0) + type.slice(1).toLowerCase()}
                      <span className={filter === type && type !== 'ALL' ? colors.text : 'text-tertiary'}>
                        {count}
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {loading && (
            <div className="flex flex-col items-center justify-center py-24 gap-3 text-secondary">
             <AccentureLoader/>
              <span className="text-sm">Loading global documents…</span>
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
                  <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
                </svg>
              </div>
              <div className="text-center">
                <p className="text-sm font-medium text-secondary">No documents yet</p>
                <p className="text-xs text-tertiary mt-1">You haven't uploaded any files across your sessions yet.</p>
              </div>
            </div>
          )}

          {!loading && !error && displayed.length > 0 && (
            <div className="flex flex-col gap-3">
              {displayed.map(att => {
                const colors  = TYPE_COLORS[att.type] || TYPE_COLORS.OTHER;
                const icon    = FILE_ICONS[att.type]  || FILE_ICONS.OTHER;
                const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';
                const fileUrl = att.url ? (att.url.startsWith('http') ? att.url : `${API_URL}${att.url}`) : null;
                const Tag     = fileUrl ? 'a' : 'div';
                const tagProps = fileUrl
                  ? { href: fileUrl, target: '_blank', rel: 'noopener noreferrer', title: `Open ${att.fileName}` }
                  : {};
                const isLink = att.type === 'WIKIPEDIA';
                return (
                  <Tag
                    key={att.attachmentId}
                    {...tagProps}
                    className={`group flex items-center gap-4 p-4 rounded-2xl border transition-all ${
                      fileUrl
                        ? 'hover:-translate-y-[2px] cursor-pointer hover:shadow-md'
                        : 'cursor-default'
                    } ${colors.bg} ${colors.border}`}
                  >
                    <div className={`shrink-0 w-11 h-11 rounded-xl flex items-center justify-center ${colors.text} bg-subtle border-t shadow-sm`}>
                      {icon}
                    </div>
                    <div className="flex-1 min-w-0 flex flex-col justify-center">
                      <p className="text-[14px] font-semibold text-primary truncate" title={att.fileName}>
                        {att.fileName || 'Unnamed file'}
                      </p>
                      <div className="flex items-center gap-3 mt-1.5">
                        <span className={`inline-flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.05em] ${colors.text}`}>
                          {isLink ? 'LINK' : att.type}
                        </span>
                        {!isLink && (
                          <>
                            <span className="w-1 h-1 rounded-full bg-slate-400/30" />
                            <span className="text-[11px] text-tertiary font-medium">{formatBytes(att.fileSizeBytes)}</span>
                          </>
                        )}
                        <span className="hidden sm:inline-flex items-center gap-3">
                          <span className="w-1 h-1 rounded-full bg-slate-400/30" />
                          <span className="text-[11px] text-tertiary">{formatDate(att.uploadedAt)}</span>
                        </span>
                      </div>
                    </div>
                    {fileUrl && (
                      <svg className="w-4 h-4 text-tertiary shrink-0 opacity-0 group-hover:opacity-100 transition-opacity" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
                        <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 6h-3a3 3 0 00-3 3v9a3 3 0 003 3h9a3 3 0 003-3v-3M14.25 5.25h4.5v4.5m0-4.5l-7.5 7.5" />
                      </svg>
                    )}
                    <button
                      type="button"
                      title="Remove this file"
                      disabled={deletingId === att.attachmentId}
                      onClick={(e) => {
                        e.preventDefault();
                        e.stopPropagation();
                        setDeleteTarget(att);
                      }}
                      className="shrink-0 w-7 h-7 rounded-lg flex items-center justify-center text-tertiary hover:bg-red-500/10 hover:text-red-400 transition-all disabled:opacity-60 disabled:cursor-wait cursor-pointer"
                    >
                      {deletingId === att.attachmentId ? (
                        <svg className="w-3.5 h-3.5 animate-spin" viewBox="0 0 24 24" fill="none">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                        </svg>
                      ) : (
                        <CloseIcon />
                      )}
                    </button>
                  </Tag>
                );
              })}

              {hasMore && (
                <button
                  onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
                  className="self-center mt-2 px-4 py-2 rounded-xl text-[12px] font-medium border t-border text-secondary interactive"
                >
                  Load more ({filtered.length - displayed.length} remaining)
                </button>
              )}
            </div>
          )}

          {!loading && !error && attachments.length > 0 && filtered.length === 0 && (
            <div className="flex flex-col items-center justify-center py-20 gap-3 text-secondary">
              <p className="text-sm">No files found matching your search.</p>
              <button onClick={() => { setFilter('ALL'); setSearchQuery(''); }} className="text-xs text-blue-500 hover:text-blue-400">
                Clear filters
              </button>
            </div>
          )}
        </div>
      </div>

      <Modal
        isOpen={deleteTarget !== null}
        title="Remove File"
        onClose={() => setDeleteTarget(null)}
        size="sm"
        footer={
          <>
            <button
              onClick={() => setDeleteTarget(null)}
              className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl transition-all cursor-pointer"
            >
              Cancel
            </button>
            <button
              onClick={confirmDelete}
              className="px-4 py-2.5 text-xs font-semibold text-white bg-red-600 hover:bg-red-500 active:scale-95 rounded-xl shadow-lg shadow-red-500/20 transition-all cursor-pointer"
            >
              Remove
            </button>
          </>
        }
      >
        <p className="text-[13px] t-text-muted">
          Remove <span className="font-semibold text-primary">{deleteTarget?.fileName || 'this file'}</span> from your Explore list?
          If no other user has uploaded this exact file, it will be deleted everywhere — the stored file, its search index, and all citation data. If someone else also uploaded it, it'll just be removed from your list and kept for them.
        </p>
      </Modal>
    </div>
  );
}
