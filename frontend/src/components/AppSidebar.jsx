import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { useToast } from '../context/ToastContext.jsx';
import Modal from './Modal.jsx';

// ─── Icons ────────────────────────────────────────────────────────────────────

const PanelIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <line x1="9" y1="3" x2="9" y2="21" />
  </svg>
);

const PlusIcon = () => (
  <svg className="w-[16px] h-[16px]" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
  </svg>
);

const ChatIcon = () => (
  <svg className="w-[14px] h-[14px] shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 12.76c0 1.6 1.123 2.994 2.707 3.227 1.087.16 2.185.283 3.293.369V21l4.076-4.076a1.526 1.526 0 011.037-.443 48.282 48.282 0 005.68-.494c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
  </svg>
);

const TrashIcon = () => (
  <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
  </svg>
);

const DotsIcon = () => (
  <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 24 24">
    <circle cx="12" cy="5" r="1.5" />
    <circle cx="12" cy="12" r="1.5" />
    <circle cx="12" cy="19" r="1.5" />
  </svg>
);

const PaperclipIcon = () => (
  <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
  </svg>
);

const ShareIcon = () => (
  <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <circle cx="18" cy="5" r="3" />
    <circle cx="6" cy="12" r="3" />
    <circle cx="18" cy="19" r="3" />
    <line x1="8.59" y1="13.51" x2="15.42" y2="17.49" />
    <line x1="15.41" y1="6.51" x2="8.59" y2="10.49" />
  </svg>
);

const RenameIcon = () => (
  <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L6.83 19.82a4.5 4.5 0 01-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 011.13-1.897L16.863 4.487zm0 0L19.5 7.125" />
  </svg>
);

const GearIcon = () => (
  <svg className="w-[14px] h-[14px] t-text-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8">
    <path strokeLinecap="round" strokeLinejoin="round" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.43l-1.003.828c-.293.241-.438.613-.43.992a7.723 7.723 0 010 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.43l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.991l-1.004-.827a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.28z" />
    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
  </svg>
);

const CloseIcon = () => (
  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2">
    <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
  </svg>
);

const UserAvatar = ({ name, picture }) => (
  <div className="w-7 h-7 rounded-full bg-gradient-to-br from-violet-500 to-purple-700 flex items-center justify-center text-white font-bold shrink-0 text-xs shadow-md overflow-hidden">
    {picture ? (
      <img src={picture} alt={name} className="w-full h-full object-cover" />
    ) : (
      name ? name.charAt(0).toUpperCase() : 'U'
    )}
  </div>
);

const LogoMark = () => (
  <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-md shrink-0">
    <svg width="12" height="12" viewBox="0 0 24 24" fill="white">
      <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />
    </svg>
  </div>
);

// ─── AppSidebar ───────────────────────────────────────────────────────────────

export default function AppSidebar({ expanded, setExpanded, mobileOpen, setMobileOpen }) {
  const { user, logout } = useAuth();
  const { sessions, loading, removeSession, updateSession } = useSessions();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [openMenuId, setOpenMenuId]     = useState(null);
  const [renameSessionId, setRenameSessionId] = useState(null);
  const [renameTitle, setRenameTitle]         = useState('');

  const activeId = location.pathname.startsWith('/chat/')
    ? location.pathname.split('/')[2]
    : null;

  const goNewChat  = () => { navigate('/dashboard'); setMobileOpen(false); };
  const goSession  = (id) => { navigate(`/chat/${id}`); setMobileOpen(false); };

  const handleDelete = async (e, sessionId) => {
    e.stopPropagation();
    setOpenMenuId(null);
    if (!window.confirm('Delete this session?')) return;
    try {
      await sessionService.delete(sessionId);
      removeSession(sessionId);
      showToast('Session deleted', 'success');
      if (String(activeId) === String(sessionId)) navigate('/dashboard');
    } catch (err) {
      showToast(err.message || 'Failed to delete', 'error');
    }
  };

  const handleViewAttachments = (e, sessionId) => {
    e.stopPropagation();
    setOpenMenuId(null);
    navigate(`/chat/${sessionId}/attachments`);
    setMobileOpen(false);
  };

  const toggleMenu = (e, sessionId) => {
    e.stopPropagation();
    setOpenMenuId(prev => prev === sessionId ? null : sessionId);
  };

  const handleShare = async (e, sessionId) => {
    e.stopPropagation();
    setOpenMenuId(null);
    try {
      const res = await sessionService.shareSession(sessionId);
      if (!res?.id) {
        throw new Error('Failed to generate shareable session ID.');
      }

      let origin = window.location.origin;
      if (origin.includes('localhost') || origin.includes('127.0.0.1')) {
        try {
          const ipRes = await fetch('https://api.ipify.org?format=json');
          const ipData = await ipRes.json();
          if (ipData.ip) {
            origin = origin.replace('localhost', ipData.ip).replace('127.0.0.1', ipData.ip);
          }
        } catch (_) {
          // ignore, fallback
        }
      }

      const shareUrl = `${origin}/share/${res.id}`;
      await navigator.clipboard.writeText(shareUrl);
      showToast('Share link copied to clipboard!', 'success');
    } catch (err) {
      showToast(err.message || 'Failed to share session', 'error');
    }
  };

  const handleRenameClick = (e, sessionId, currentTitle) => {
    e.stopPropagation();
    setOpenMenuId(null);
    setRenameSessionId(sessionId);
    setRenameTitle(currentTitle || '');
  };

  const handleRenameSubmit = async (e) => {
    if (e) e.preventDefault();
    if (!renameTitle.trim()) return;
    try {
      await sessionService.rename(renameSessionId, renameTitle.trim());
      updateSession(renameSessionId, { title: renameTitle.trim() });
      showToast('Session renamed successfully', 'success');
      setRenameSessionId(null);
    } catch (err) {
      showToast(err.message || 'Failed to rename session', 'error');
    }
  };

  // ── Session list ────────────────────────────────────────────────────────────

  const SessionList = ({ alwaysExpanded = false }) => {
    const show = alwaysExpanded || expanded;
    return (
      <div className="flex-1 overflow-y-auto mt-3 px-2 min-h-0" onClick={() => setOpenMenuId(null)}>
        {show && (
          <p className="text-[10px] font-semibold t-text-faint uppercase tracking-[0.12em] px-2 pb-2 select-none">
            Recent
          </p>
        )}

        {loading ? (
          <div className="flex justify-center py-6">
            <div className="w-3.5 h-3.5 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
          </div>
        ) : sessions.length === 0 ? (
          show && (
            <p className="text-xs t-text-faint text-center py-8 px-3 leading-relaxed">
              No sessions yet.<br />Start a new chat!
            </p>
          )
        ) : (
          sessions.map(s => {
            const isActive = String(activeId) === String(s.sessionId);
            const menuOpen = openMenuId === s.sessionId;
            return (
              <div
                key={s.sessionId}
                onClick={() => goSession(s.sessionId)}
                title={!show ? s.title : undefined}
                className={`group relative flex items-center gap-2.5 px-2.5 py-[9px] rounded-xl cursor-pointer transition-all duration-150 select-none mb-[2px] ${
                  isActive
                    ? 't-bg-active t-text-main'
                    : 't-text-muted hover:t-text-main t-hover-bg'
                } ${!show ? 'justify-center' : ''}`}
              >
                <ChatIcon />
                {show && (
                  <>
                    <span className="flex-1 truncate text-[13px]">{s.title}</span>

                    {/* ⋮ Three-dot menu */}
                    <button
                      onClick={(e) => toggleMenu(e, s.sessionId)}
                      className="opacity-0 group-hover:opacity-100 p-1 rounded-lg t-text-faint hover:t-text-main t-hover-bg transition-all shrink-0"
                      title="More options"
                    >
                      <DotsIcon />
                    </button>

                    {/* Dropdown */}
                    {menuOpen && (
                      <div
                        className="absolute right-1 top-8 z-50 t-bg-menu t-border-soft border rounded-xl shadow-2xl py-1 min-w-[160px]"
                        style={{ boxShadow: 'var(--shadow-elev)' }}
                        onClick={e => e.stopPropagation()}
                      >
                        <button
                          onClick={(e) => handleViewAttachments(e, s.sessionId)}
                          className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] t-text-muted hover:t-text-main t-hover-bg transition-colors"
                        >
                          <PaperclipIcon />
                          View Attachments
                        </button>
                        <div className="h-px t-border mx-2 my-0.5" />
                        <button
                          onClick={(e) => handleDelete(e, s.sessionId)}
                          className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] text-red-400 hover:text-red-300 hover:bg-red-500/[0.07] transition-colors"
                        >
                          <TrashIcon />
                          Delete session
                        </button>
                        <div className="h-px t-border mx-2 my-0.5" />
                        <button
                          onClick={(e) => handleShare(e, s.sessionId)}
                          className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] t-text-muted hover:t-text-main t-hover-bg transition-colors"
                        >
                          <ShareIcon />
                          Share
                        </button>
                        <div className="h-px t-border mx-2 my-0.5" />
                        <button
                          onClick={(e) => handleRenameClick(e, s.sessionId, s.title)}
                          className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] t-text-muted hover:t-text-main t-hover-bg transition-colors"
                        >
                          <RenameIcon />
                          Rename
                        </button>
                      </div>
                    )}
                  </>
                )}
              </div>
            );
          })
        )}
      </div>
    );
  };

  // ── User footer ──────────────────────────────────────────────────────────────

  const UserFooter = ({ show }) => (
    <div className="shrink-0 border-t t-border p-2 relative">
      {settingsOpen && show && (
        <div
          className="absolute bottom-[68px] left-2 right-2 t-bg-menu t-border-soft border rounded-2xl py-1.5 shadow-2xl z-50"
          style={{ boxShadow: 'var(--shadow-elev)' }}
          onClick={e => e.stopPropagation()}
        >
          <button
            onClick={() => { setSettingsOpen(false); navigate('/settings'); }}
            className="w-full text-left px-4 py-2.5 text-[13px] t-text-muted hover:t-text-main t-hover-bg transition-colors rounded-xl"
          >
            Settings
          </button>
          <div className="h-px t-border mx-3 my-1" />
          <button
            onClick={() => { setSettingsOpen(false); logout(); navigate('/login'); }}
            className="w-full text-left px-4 py-2.5 text-[13px] text-red-400 hover:text-red-300 hover:bg-red-500/[0.07] transition-colors rounded-xl font-medium"
          >
            Sign out
          </button>
        </div>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); setSettingsOpen(v => !v); }}
        title={!show ? (user?.name || 'User') : undefined}
        className={`flex items-center gap-3 w-full p-2 rounded-xl t-hover-bg transition-all cursor-pointer ${!show ? 'justify-center' : ''}`}
      >
        <UserAvatar name={user?.name} picture={user?.profileImageUrl} />
        {show && (
          <>
            <div className="flex-1 min-w-0 text-left">
              <p className="text-[13px] font-medium t-text-main truncate leading-tight">{user?.name || 'User'}</p>
              <p className="text-[11px] t-text-faint truncate leading-tight">{user?.email || ''}</p>
            </div>
            <GearIcon />
          </>
        )}
      </button>
    </div>
  );

  // ─────────────────────────────────────────────────────────────────────────────

  return (
    <>
      {/* ── Mobile overlay ── */}
      <div
        className={`fixed inset-0 bg-black/50 backdrop-blur-sm z-30 md:hidden transition-opacity duration-300 ${
          mobileOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'
        }`}
        onClick={() => setMobileOpen(false)}
      />

      {/* ── Mobile drawer ── */}
      <aside
        className={`fixed inset-y-0 left-0 z-40 w-72 flex flex-col t-bg-sidebar border-r t-border md:hidden transition-transform duration-300 ease-out ${
          mobileOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
        onClick={() => settingsOpen && setSettingsOpen(false)}
      >
        <div className="flex items-center justify-between h-14 px-4 shrink-0">
          <div className="flex items-center gap-2.5">
            <LogoMark />
            <span className="text-[15px] font-semibold t-text-main tracking-tight">DocuMind</span>
          </div>
          <button onClick={() => setMobileOpen(false)} className="p-1.5 rounded-xl t-text-muted hover:t-text-main t-hover-bg transition-all">
            <CloseIcon />
          </button>
        </div>
        <div className="px-2 mt-1 shrink-0">
          <button
            onClick={goNewChat}
            className="flex items-center gap-3 w-full px-3 py-2.5 text-[13px] font-medium t-text-main t-bg-hover hover:t-bg-active rounded-xl border t-border transition-all cursor-pointer"
          >
            <PlusIcon />
            New chat
          </button>
        </div>
        <SessionList alwaysExpanded />
        <UserFooter show />
      </aside>

      {/* ── Desktop sidebar (collapsible) ── */}
      <aside
        className={`hidden md:flex flex-col h-full t-bg-sidebar border-r t-border shrink-0 transition-all duration-300 ease-in-out overflow-hidden ${
          expanded ? 'w-[248px]' : 'w-[62px]'
        }`}
        onClick={() => settingsOpen && setSettingsOpen(false)}
      >
        {/* Header */}
        <div className={`flex items-center h-14 shrink-0 px-2.5 ${expanded ? 'justify-between' : 'justify-center'}`}>
          {expanded && (
            <div className="flex items-center gap-2.5 pl-1">
              <LogoMark />
              <span className="text-[15px] font-semibold t-text-main tracking-tight whitespace-nowrap">DocuMind</span>
            </div>
          )}
          <button
            onClick={() => setExpanded(v => !v)}
            className="p-2 rounded-xl t-text-faint hover:t-text-main t-hover-bg transition-all"
            title={expanded ? 'Collapse' : 'Expand'}
          >
            <PanelIcon />
          </button>
        </div>

        {/* New chat */}
        <div className="px-2 mt-1 shrink-0">
          <button
            onClick={goNewChat}
            title={!expanded ? 'New chat' : undefined}
            className={`flex items-center gap-3 w-full px-2.5 py-[10px] text-[13px] font-medium t-text-main t-bg-hover hover:t-bg-active rounded-xl border t-border transition-all active:scale-[0.97] cursor-pointer ${!expanded ? 'justify-center' : ''}`}
          >
            <PlusIcon />
            {expanded && <span className="whitespace-nowrap">New chat</span>}
          </button>
        </div>

        <SessionList />
        <UserFooter show={expanded} />
      </aside>

      <Modal
        isOpen={renameSessionId !== null}
        title="Rename Session"
        onClose={() => setRenameSessionId(null)}
        footer={
          <>
            <button
              onClick={() => setRenameSessionId(null)}
              className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl transition-all cursor-pointer"
            >
              Cancel
            </button>
            <button
              onClick={handleRenameSubmit}
              disabled={!renameTitle.trim()}
              className="px-4 py-2.5 text-xs font-semibold text-white bg-blue-600 hover:bg-blue-500 active:scale-95 disabled:scale-100 disabled:opacity-40 rounded-xl shadow-lg shadow-blue-500/20 transition-all cursor-pointer"
            >
              Rename
            </button>
          </>
        }
      >
        <form onSubmit={handleRenameSubmit} className="flex flex-col gap-2.5">
          <label className="text-[10px] font-semibold t-text-faint uppercase tracking-wider pl-1">
            Session Title
          </label>
          <input
            type="text"
            value={renameTitle}
            onChange={(e) => setRenameTitle(e.target.value)}
            className="w-full input-bg rounded-xl px-3.5 py-2.5 text-[13px] outline-none focus:border-blue-500/40 transition-all placeholder:t-text-faint"
            placeholder="Enter new title..."
            autoFocus
          />
        </form>
      </Modal>
    </>
  );
}
