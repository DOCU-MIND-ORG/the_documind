import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useSessions } from '../context/SessionsContext.jsx';
import { sessionService } from '../services/sessionService.js';
import { folderService } from '../services/folderService.js';
import { useToast } from '../context/ToastContext.jsx';
import Modal from './Modal.jsx';
import GlowingDot from './GlowingDot.jsx';

// ─── Icons ────────────────────────────────────────────────────────────────────
const PanelIcon = () => <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8"><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><line x1="9" y1="3" x2="9" y2="21" /></svg>;
const PlusIcon = () => <svg className="w-[16px] h-[16px]" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.2"><path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" /></svg>;
const GlobeIcon = () => <svg className="w-[16px] h-[16px]" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M12 21a9.004 9.004 0 008.716-6.747M12 21a9.004 9.004 0 01-8.716-6.747M12 21c2.485 0 4.5-4.03 4.5-9S14.485 3 12 3m0 18c-2.485 0-4.5-4.03-4.5-9S9.515 3 12 3m0 0a8.997 8.997 0 017.843 4.582M12 3a8.997 8.997 0 00-7.843 4.582m15.686 0A11.953 11.953 0 0112 10.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0A8.959 8.959 0 0121 12c0 .778-.099 1.533-.284 2.253m0 0A11.954 11.954 0 0112 16.5c-2.998 0-5.74-1.1-7.843-2.918m15.686 0a8.959 8.959 0 01-2.253 2.253" /></svg>;
const FolderPlusIcon = () => <svg className="w-[16px] h-[16px]" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M12 10.5v6m3-3H9m4.06-7.19l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z" /></svg>;
const FolderSolidIcon = ({ color }) => (
  <svg className="w-[16px] h-[16px] shrink-0" fill={color || 'currentColor'} viewBox="0 0 24 24">
    <path d="M19.5 21a3 3 0 0 0 3-3v-4.5a3 3 0 0 0-3-3h-15a3 3 0 0 0-3 3V18a3 3 0 0 0 3 3h15ZM1.5 10.146V6a3 3 0 0 1 3-3h5.379a2.25 2.25 0 0 1 1.59.659l2.122 2.121c.14.141.331.22.53.22H19.5a3 3 0 0 1 3 3v1.146A4.483 4.483 0 0 0 19.5 9h-15a4.483 4.483 0 0 0-3 1.146Z" />
  </svg>
);
const TrashIcon = () => <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>;
const DotsIcon = () => <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="12" cy="19" r="1.5" /></svg>;
const PaperclipIcon = () => <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" /></svg>;
const RenameIcon = () => <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L6.83 19.82a4.5 4.5 0 01-1.897 1.13l-2.685.8.8-2.685a4.5 4.5 0 011.13-1.897L16.863 4.487zm0 0L19.5 7.125" /></svg>;
const GearIcon = () => <svg className="w-[14px] h-[14px] t-text-faint" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.8"><path strokeLinecap="round" strokeLinejoin="round" d="M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 011.37.49l1.296 2.247a1.125 1.125 0 01-.26 1.43l-1.003.828c-.293.241-.438.613-.43.992a7.723 7.723 0 010 .255c-.008.378.137.75.43.991l1.004.827c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 01-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 01-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 01-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 01-1.369-.49l-1.297-2.247a1.125 1.125 0 01.26-1.43l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 010-.255c.007-.378-.138-.75-.43-.991l-1.004-.827a1.125 1.125 0 01-.26-1.43l1.297-2.247a1.125 1.125 0 011.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.28z" /><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>;
const CloseIcon = () => <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2"><path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" /></svg>;
const ChevronRightIcon = ({ expanded }) => <svg className={`w-3 h-3 transition-transform ${expanded ? 'rotate-90' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="2.5"><path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" /></svg>;

const UserAvatar = ({ name, picture }) => (
  <div className="w-7 h-7 rounded-full bg-gradient-to-br from-violet-500 to-purple-700 flex items-center justify-center text-white font-bold shrink-0 text-xs shadow-md overflow-hidden">
    {picture ? <img src={picture} alt={name} className="w-full h-full object-cover" /> : (name ? name.charAt(0).toUpperCase() : 'U')}
  </div>
);

const LogoMark = () => (
  <div className="w-6 h-6 flex items-center justify-center shrink-0">
    <img src="/light.png" className="w-full h-full object-contain dark:hidden" alt="Logo" />
    <img src="/dark.png" className="w-full h-full object-contain hidden dark:block" alt="Logo" />
  </div>
);

// ─── AppSidebar ───────────────────────────────────────────────────────────────

export default function AppSidebar({ expanded, setExpanded, mobileOpen, setMobileOpen }) {
  const { user, logout } = useAuth();
  const { sessions, folders, ungroupedSessions, loading, removeSession, updateSession, addFolder, removeFolder, updateFolder, addSessionToFolder, removeSessionFromFolder } = useSessions();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const location = useLocation();

  const [settingsOpen, setSettingsOpen] = useState(false);
  const [openMenuId, setOpenMenuId]     = useState(null);
  
  // Modals state
  const [renameSessionId, setRenameSessionId] = useState(null);
  const [renameTitle, setRenameTitle]         = useState('');
  const [deleteSessionId, setDeleteSessionId] = useState(null);
  const [isDeleting, setIsDeleting]           = useState(false);
  
  const [createFolderOpen, setCreateFolderOpen] = useState(false);
  const [folderForm, setFolderForm] = useState({ name: '', colorHex: '#3b82f6', sessionIds: [] });
  const [editFolderId, setEditFolderId] = useState(null);
  const [deleteFolderId, setDeleteFolderId] = useState(null);

  const activeId = location.pathname.startsWith('/chat/') ? location.pathname.split('/')[2] : null;

  const goNewChat  = () => { navigate('/dashboard'); setMobileOpen(false); };
  const goExplore  = () => { navigate('/explore'); setMobileOpen(false); };
  const goSession  = (id) => { navigate(`/chat/${id}`); setMobileOpen(false); };

  // ── Drag & Drop Handlers ──────────────────────────────────────────────────
  const onDragStart = (e, sessionId, sourceFolderId = null) => {
    e.dataTransfer.setData('sessionId', sessionId);
    if (sourceFolderId) e.dataTransfer.setData('sourceFolderId', sourceFolderId);
    e.dataTransfer.effectAllowed = 'move';
  };

  const onDragOver = (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
  };

  const onDropFolder = (e, folderId) => {
    e.preventDefault();
    e.stopPropagation();
    const sessionId = e.dataTransfer.getData('sessionId');
    if (sessionId) {
      addSessionToFolder(Number(sessionId), folderId);
    }
  };

  const onDropRecent = (e) => {
    e.preventDefault();
    e.stopPropagation();
    const sessionId = e.dataTransfer.getData('sessionId');
    const sourceFolderId = e.dataTransfer.getData('sourceFolderId');
    if (sessionId && sourceFolderId) {
      removeSessionFromFolder(Number(sessionId), Number(sourceFolderId));
    }
  };

  // ── Session Operations ───────────────────────────────────────────────────
  const handleConfirmDelete = async () => {
    if (!deleteSessionId || isDeleting) return;
    setIsDeleting(true);
    try {
      await sessionService.delete(deleteSessionId);
      removeSession(deleteSessionId);
      showToast('Session deleted', 'success');
      if (String(activeId) === String(deleteSessionId)) navigate('/dashboard');
    } catch (err) {
      showToast(err.message || 'Failed to delete', 'error');
    } finally {
      setIsDeleting(false);
      setDeleteSessionId(null);
    }
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

  // ── Folder Operations ────────────────────────────────────────────────────
  const handleCreateFolder = async (e) => {
    e.preventDefault();
    if (!folderForm.name.trim()) return;
    try {
      const newFolder = await folderService.create(folderForm);
      addFolder(newFolder);
      showToast('Folder created', 'success');
      setCreateFolderOpen(false);
      setFolderForm({ name: '', colorHex: '#3b82f6', sessionIds: [] });
    } catch (err) {
      showToast(err.message || 'Failed to create folder', 'error');
    }
  };

  const handleConfirmDeleteFolder = async () => {
    if (!deleteFolderId) return;
    try {
      await folderService.delete(deleteFolderId);
      removeFolder(deleteFolderId);
      showToast('Folder deleted', 'success');
    } catch (err) {
      showToast(err.message || 'Failed to delete folder', 'error');
    } finally {
      setDeleteFolderId(null);
    }
  };

  // ── Components ───────────────────────────────────────────────────────────
  const SessionItem = ({ s, show, folderId = null }) => {
    const isActive = String(activeId) === String(s.sessionId);
    const menuOpen = openMenuId === (folderId ? `f${folderId}-s${s.sessionId}` : `s-${s.sessionId}`);
    return (
      <div
        draggable
        onDragStart={(e) => onDragStart(e, s.sessionId, folderId)}
        onClick={() => goSession(s.sessionId)}
        title={!show ? s.title : undefined}
        className={`group relative flex items-center gap-2.5 px-2.5 py-[9px] rounded-xl cursor-pointer transition-all duration-150 select-none mb-[2px] ${
          isActive ? 't-bg-active t-text-main' : 't-text-muted hover:t-text-main t-hover-bg'
        } ${!show ? 'justify-center' : ''}`}
      >
        <GlowingDot sessionId={s.sessionId} />
        {show && (
          <>
            <span className="flex-1 truncate text-[13px]">{s.title}</span>
            <button
              onClick={(e) => { e.stopPropagation(); setOpenMenuId(menuOpen ? null : (folderId ? `f${folderId}-s${s.sessionId}` : `s-${s.sessionId}`)); }}
              className="p-1 rounded-lg t-text-faint hover:t-text-main t-hover-bg transition-all shrink-0 opacity-0 group-hover:opacity-100"
            >
              <DotsIcon />
            </button>
            {menuOpen && (
              <div className="absolute right-1 top-8 z-50 t-bg-menu t-border-soft border rounded-xl shadow-2xl py-1 min-w-[160px]" onClick={e => e.stopPropagation()}>
                <button onClick={() => { setOpenMenuId(null); navigate(`/chat/${s.sessionId}/attachments`); setMobileOpen(false); }} className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] t-text-muted hover:t-text-main t-hover-bg transition-colors">
                  <PaperclipIcon /> View Attachments
                </button>
                <div className="h-px t-border mx-2 my-0.5" />
                <button onClick={() => { setOpenMenuId(null); setRenameSessionId(s.sessionId); setRenameTitle(s.title); }} className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] t-text-muted hover:t-text-main t-hover-bg transition-colors">
                  <RenameIcon /> Rename
                </button>
                {folderId && (
                  <button onClick={() => { setOpenMenuId(null); removeSessionFromFolder(s.sessionId, folderId); }} className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] t-text-muted hover:t-text-main t-hover-bg transition-colors">
                    <TrashIcon /> Remove from folder
                  </button>
                )}
                <button onClick={() => { setOpenMenuId(null); setDeleteSessionId(s.sessionId); }} className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] text-red-400 hover:text-red-300 hover:bg-red-500/[0.07] transition-colors">
                  <TrashIcon /> Delete session
                </button>
              </div>
            )}
          </>
        )}
      </div>
    );
  };

  const FolderItem = ({ folder, show }) => {
    const [isExpanded, setIsExpanded] = useState(() => localStorage.getItem(`folder_expanded_${folder.id}`) !== 'false');
    const toggleFolder = () => {
      const next = !isExpanded;
      setIsExpanded(next);
      localStorage.setItem(`folder_expanded_${folder.id}`, next);
    };

    const menuOpen = openMenuId === `f-${folder.id}`;

    return (
      <div 
        className="mb-1"
        onDragOver={onDragOver}
        onDrop={(e) => onDropFolder(e, folder.id)}
      >
        <div
          onClick={show ? toggleFolder : () => { setExpanded(true); setIsExpanded(true); }}
          title={!show ? folder.name : undefined}
          className={`group flex items-center gap-2 px-2.5 py-2 rounded-xl cursor-pointer transition-all t-text-main hover:t-bg-active ${!show ? 'justify-center' : ''}`}
        >
          {show && (
            <div className="t-text-faint w-4 flex justify-center">
              <ChevronRightIcon expanded={isExpanded} />
            </div>
          )}
          <FolderSolidIcon color={folder.colorHex} />
          {show && (
            <>
              <span className="flex-1 truncate text-[13px] font-medium">{folder.name}</span>
              <button
                onClick={(e) => { e.stopPropagation(); setOpenMenuId(menuOpen ? null : `f-${folder.id}`); }}
                className="p-1 rounded-lg t-text-faint hover:t-text-main t-hover-bg transition-all shrink-0 opacity-0 group-hover:opacity-100"
              >
                <DotsIcon />
              </button>
              {menuOpen && (
                <div className="absolute right-6 z-50 t-bg-menu t-border-soft border rounded-xl shadow-2xl py-1 min-w-[140px]" onClick={e => e.stopPropagation()}>
                  <button onClick={() => { setOpenMenuId(null); setDeleteFolderId(folder.id); }} className="flex items-center gap-2.5 w-full text-left px-3 py-2 text-[12px] text-red-400 hover:text-red-300 hover:bg-red-500/[0.07] transition-colors">
                    <TrashIcon /> Delete folder
                  </button>
                </div>
              )}
            </>
          )}
        </div>
        
        {show && isExpanded && folder.sessions && folder.sessions.length > 0 && (
          <div className="pl-6 pr-1 mt-1 border-l-2 ml-4 border-transparent hover:border-gray-200 dark:hover:border-gray-800 transition-colors">
            {folder.sessions.map(s => <SessionItem key={s.sessionId} s={s} show={show} folderId={folder.id} />)}
          </div>
        )}
        {show && isExpanded && (!folder.sessions || folder.sessions.length === 0) && (
          <div className="pl-10 text-[11px] t-text-faint py-1">Empty folder</div>
        )}
      </div>
    );
  };

  const UserFooter = ({ show }) => (
    <div className="shrink-0 border-t t-border p-2 relative">
      {settingsOpen && show && (
        <div className="absolute bottom-[68px] left-2 right-2 t-bg-menu t-border-soft border rounded-2xl py-1.5 shadow-2xl z-50">
          <button onClick={() => { setSettingsOpen(false); setMobileOpen(false); navigate('/settings'); }} className="w-full text-left px-4 py-2.5 text-[13px] t-text-main hover:bg-black/5 dark:hover:bg-white/5 transition-colors font-medium">
            Settings
          </button>
          <div className="h-px t-border mx-2 my-1" />
          <button onClick={() => { setSettingsOpen(false); setMobileOpen(false); logout(); navigate('/login'); }} className="w-full text-left px-4 py-2.5 text-[13px] text-red-500 hover:bg-red-500/[0.07] transition-colors font-medium">
            Sign out
          </button>
        </div>
      )}
      <button onClick={(e) => { e.stopPropagation(); setSettingsOpen(v => !v); }} className={`flex items-center gap-3 w-full p-2 rounded-xl t-hover-bg transition-all cursor-pointer ${!show ? 'justify-center' : ''}`}>
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

  return (
    <>
      <div className={`fixed inset-0 bg-black/50 backdrop-blur-sm z-[60] md:hidden transition-opacity duration-300 ${mobileOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none'}`} onClick={() => setMobileOpen(false)} />
      
      <aside className={`fixed md:relative inset-y-0 left-0 z-[70] flex flex-col h-full t-bg-sidebar border-r t-border transition-all duration-300 ease-in-out overflow-hidden ${mobileOpen ? 'translate-x-0 w-72' : '-translate-x-full md:translate-x-0'} ${expanded || mobileOpen ? 'md:w-[248px]' : 'md:w-[62px]'}`} onClick={() => settingsOpen && setSettingsOpen(false)}>
        <div className={`flex items-center h-14 shrink-0 px-2.5 ${expanded || mobileOpen ? 'justify-between' : 'justify-center'}`}>
          {(expanded || mobileOpen) && (
            <div className="flex items-center gap-2.5 pl-1">
              <LogoMark /><span className="text-[15px] font-semibold t-text-main tracking-tight whitespace-nowrap">DocuMind</span>
            </div>
          )}
          {!mobileOpen && (
            <button onClick={() => setExpanded(v => !v)} className="p-2 rounded-xl t-text-faint hover:t-text-main t-hover-bg transition-all">
              <PanelIcon />
            </button>
          )}
          {mobileOpen && (
            <button onClick={() => setMobileOpen(false)} className="p-1.5 rounded-xl t-text-muted hover:t-text-main t-hover-bg transition-all">
              <CloseIcon />
            </button>
          )}
        </div>

        <div 
          className="flex-1 overflow-y-auto px-2 min-h-0 pb-4" 
          onClick={() => setOpenMenuId(null)}
          onDragOver={onDragOver}
          onDrop={onDropRecent}
        >
          <div className="mt-2 shrink-0 flex flex-col gap-2">
            <button onClick={goNewChat} className={`flex items-center gap-3 w-full px-2.5 py-[10px] text-[13px] font-medium t-text-main rounded-xl t-hover-bg transition-all cursor-pointer ${!(expanded || mobileOpen) ? 'justify-center' : ''}`}>
              <PlusIcon /> {(expanded || mobileOpen) && <span>New chat</span>}
            </button>
            <button onClick={goExplore} className={`flex items-center gap-3 w-full px-2.5 py-[10px] text-[13px] font-medium t-text-main rounded-xl transition-all cursor-pointer ${!(expanded || mobileOpen) ? 'justify-center' : ''} ${location.pathname === '/explore' ? 't-bg-active' : 't-hover-bg'}`}>
              <GlobeIcon /> {(expanded || mobileOpen) && <span>Explore</span>}
            </button>
            <button onClick={() => setCreateFolderOpen(true)} className={`flex items-center gap-3 w-full px-2.5 py-[10px] text-[13px] font-medium t-text-main rounded-xl t-hover-bg transition-all cursor-pointer ${!(expanded || mobileOpen) ? 'justify-center' : ''}`}>
              <FolderPlusIcon /> {(expanded || mobileOpen) && <span>New folder</span>}
            </button>
          </div>

          <div className="mt-4">
            {loading ? (
              <div className="flex justify-center py-6"><div className="w-3.5 h-3.5 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" /></div>
            ) : (
              <>
                {folders.map(f => <FolderItem key={f.id} folder={f} show={expanded || mobileOpen} />)}
                
                {(expanded || mobileOpen) && folders.length > 0 && sessions.length > 0 && (
                  <div className="h-px t-border mx-2 my-3" />
                )}
                
                <div className="min-h-[50px] pb-4">
                  {(expanded || mobileOpen) && (
                    <p className="text-[10px] font-semibold t-text-faint uppercase tracking-[0.12em] px-2 pb-2 mt-2 select-none">
                      Recent
                    </p>
                  )}
                  {sessions.map(s => <SessionItem key={s.sessionId} s={s} show={expanded || mobileOpen} />)}
                  {sessions.length === 0 && (expanded || mobileOpen) && (
                    <p className="text-xs t-text-faint text-center py-8 px-3">No sessions yet.<br />Start a new chat!</p>
                  )}
                </div>
              </>
            )}
          </div>
        </div>
        <UserFooter show={expanded || mobileOpen} />
      </aside>

      {/* Modals */}
      <Modal isOpen={renameSessionId !== null} title="Rename Session" onClose={() => setRenameSessionId(null)} footer={
          <><button onClick={() => setRenameSessionId(null)} className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl">Cancel</button>
          <button onClick={handleRenameSubmit} disabled={!renameTitle.trim()} className="px-4 py-2.5 text-xs font-semibold text-white bg-blue-600 hover:bg-blue-500 rounded-xl">Rename</button></>
        }>
        <input type="text" value={renameTitle} onChange={(e) => setRenameTitle(e.target.value)} className="w-full input-bg rounded-xl px-3.5 py-2.5 text-[13px] outline-none border t-border" placeholder="New title..." autoFocus />
      </Modal>

      <Modal isOpen={deleteSessionId !== null} title="Delete Session" onClose={() => setDeleteSessionId(null)} footer={
          <><button onClick={() => setDeleteSessionId(null)} className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl">Cancel</button>
          <button onClick={handleConfirmDelete} disabled={isDeleting} className="px-4 py-2.5 text-xs font-semibold text-white bg-red-600 hover:bg-red-500 rounded-xl">Delete</button></>
        }>
        <p className="text-[13px] t-text-muted">Are you sure you want to delete this session?</p>
      </Modal>

      <Modal isOpen={deleteFolderId !== null} title="Delete Folder" onClose={() => setDeleteFolderId(null)} footer={
          <><button onClick={() => setDeleteFolderId(null)} className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl">Cancel</button>
          <button onClick={handleConfirmDeleteFolder} className="px-4 py-2.5 text-xs font-semibold text-white bg-red-600 hover:bg-red-500 rounded-xl">Delete</button></>
        }>
        <p className="text-[13px] t-text-muted">Delete this folder? The sessions inside will be kept and moved to Recent.</p>
      </Modal>

      <Modal isOpen={createFolderOpen} title="New Folder" onClose={() => setCreateFolderOpen(false)} footer={
          <><button onClick={() => setCreateFolderOpen(false)} className="px-4 py-2.5 text-xs font-semibold t-text-muted hover:t-text-main t-hover-bg rounded-xl">Cancel</button>
          <button onClick={handleCreateFolder} disabled={!folderForm.name.trim()} className="px-4 py-2.5 text-xs font-semibold text-white bg-blue-600 hover:bg-blue-500 rounded-xl">Create</button></>
        }>
        <form onSubmit={handleCreateFolder} className="flex flex-col gap-3">
          <input type="text" value={folderForm.name} onChange={e => setFolderForm({...folderForm, name: e.target.value})} className="w-full input-bg rounded-xl px-3.5 py-2 text-[13px] border t-border" placeholder="Folder name" autoFocus />
          <div className="flex gap-2 items-center">
            <span className="text-xs font-medium t-text-muted px-1">Color:</span>
            <input type="color" value={folderForm.colorHex} onChange={e => setFolderForm({...folderForm, colorHex: e.target.value})} className="w-12 h-9 rounded cursor-pointer" />
          </div>
          {sessions.length > 0 && (
            <div className="mt-2 max-h-40 overflow-y-auto border t-border rounded-xl p-2">
              <p className="text-xs t-text-faint mb-2 font-medium">Select initial sessions</p>
              {sessions.map(s => (
                <label key={s.sessionId} className="flex items-center gap-2 py-1 px-1 hover:t-bg-active rounded cursor-pointer">
                  <input type="checkbox" checked={folderForm.sessionIds.includes(s.sessionId)} onChange={e => {
                    const ids = e.target.checked ? [...folderForm.sessionIds, s.sessionId] : folderForm.sessionIds.filter(id => id !== s.sessionId);
                    setFolderForm({...folderForm, sessionIds: ids});
                  }} />
                  <span className="text-[12px] truncate">{s.title}</span>
                </label>
              ))}
            </div>
          )}
        </form>
      </Modal>
    </>
  );
}
