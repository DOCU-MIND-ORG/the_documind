import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { sessionService } from '../services/sessionService.js';
import { folderService } from '../services/folderService.js';

const SessionsContext = createContext(null);

export function SessionsProvider({ children }) {
  const [sessions, setSessions] = useState([]); // All sessions
  const [folders, setFolders] = useState([]);
  const [loading, setLoading] = useState(true);

  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const [sessionsData, foldersData] = await Promise.all([
        sessionService.getAll(),
        folderService.getAll()
      ]);
      setSessions(sessionsData);
      setFolders(foldersData);
    } catch (err) {
      console.error('Failed to load sessions/folders', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const addSession = useCallback((s) => {
    setSessions(prev => [s, ...prev.filter(p => p.sessionId !== s.sessionId)]);
    if (s.folderIds && s.folderIds.length > 0) {
      setFolders(prev => prev.map(f => s.folderIds.includes(f.id) ? { ...f, sessions: [s, ...f.sessions.filter(p => p.sessionId !== s.sessionId)] } : f));
    }
  }, []);

  const removeSession = useCallback((id) => {
    setSessions(prev => prev.filter(s => s.sessionId !== id));
    setFolders(prev => prev.map(f => ({ ...f, sessions: f.sessions.filter(s => s.sessionId !== id) })));
  }, []);

  const updateSession = useCallback((id, patch) => {
    setSessions(prev => prev.map(s => s.sessionId === id ? { ...s, ...patch } : s));
    setFolders(prev => prev.map(f => ({
      ...f,
      sessions: f.sessions.map(s => s.sessionId === id ? { ...s, ...patch } : s)
    })));
  }, []);

  const addFolder = useCallback((f) => {
    setFolders(prev => [...prev, f]);
    if (f.sessions && f.sessions.length > 0) {
      const sessionIds = f.sessions.map(s => s.sessionId);
      setSessions(prev => prev.map(s => sessionIds.includes(s.sessionId) ? { ...s, folderIds: [...(s.folderIds || []), f.id] } : s));
    }
  }, []);

  const updateFolder = useCallback((id, patch) => {
    setFolders(prev => prev.map(f => f.id === id ? { ...f, ...patch } : f));
  }, []);

  const removeFolder = useCallback((id) => {
    setFolders(prev => prev.filter(f => f.id !== id));
    // When a folder is deleted, remove it from the sessions' folderIds
    setSessions(prev => prev.map(s => s.folderIds && s.folderIds.includes(id) ? { ...s, folderIds: s.folderIds.filter(fid => fid !== id) } : s));
  }, []);

  const addSessionToFolder = useCallback(async (sessionId, folderId) => {
    // Optimistic update
    const session = sessions.find(s => s.sessionId === sessionId);
    if (!session) return;
    
    if (session.folderIds && session.folderIds.includes(folderId)) return; // Already in folder

    // Update flat sessions list
    setSessions(prev => prev.map(s => s.sessionId === sessionId ? { ...s, folderIds: [...(s.folderIds || []), folderId] } : s));
    
    // Update folders
    setFolders(prev => prev.map(f => {
      if (f.id === folderId) {
        return { ...f, sessions: [{ ...session, folderIds: [...(session.folderIds || []), folderId] }, ...f.sessions] };
      }
      return f;
    }));

    try {
      await sessionService.patch(sessionId, { addFolderId: folderId });
    } catch (err) {
      console.error('Failed to add session to folder', err);
      // Rollback
      setSessions(prev => prev.map(s => s.sessionId === sessionId ? { ...s, folderIds: s.folderIds.filter(fid => fid !== folderId) } : s));
      setFolders(prev => prev.map(f => {
        if (f.id === folderId) {
          return { ...f, sessions: f.sessions.filter(s => s.sessionId !== sessionId) };
        }
        return f;
      }));
    }
  }, [sessions]);

  const removeSessionFromFolder = useCallback(async (sessionId, folderId) => {
    // Optimistic update
    const session = sessions.find(s => s.sessionId === sessionId);
    if (!session) return;
    
    // Update flat sessions list
    setSessions(prev => prev.map(s => s.sessionId === sessionId ? { ...s, folderIds: (s.folderIds || []).filter(fid => fid !== folderId) } : s));
    
    // Update folders
    setFolders(prev => prev.map(f => {
      if (f.id === folderId) {
        return { ...f, sessions: f.sessions.filter(s => s.sessionId !== sessionId) };
      }
      return f;
    }));

    try {
      await sessionService.patch(sessionId, { removeFolderId: folderId });
    } catch (err) {
      console.error('Failed to remove session from folder', err);
      // Rollback
      setSessions(prev => prev.map(s => s.sessionId === sessionId ? { ...s, folderIds: [...(s.folderIds || []), folderId] } : s));
      setFolders(prev => prev.map(f => {
        if (f.id === folderId) {
          return { ...f, sessions: [{ ...session, folderIds: [...(session.folderIds || []), folderId] }, ...f.sessions] };
        }
        return f;
      }));
    }
  }, [sessions]);

  const ungroupedSessions = sessions.filter(s => !s.folderIds || s.folderIds.length === 0);

  return (
    <SessionsContext.Provider value={{ 
      sessions, 
      ungroupedSessions, 
      folders, 
      loading, 
      addSession, 
      removeSession, 
      updateSession,
      addFolder,
      updateFolder,
      removeFolder,
      addSessionToFolder,
      removeSessionFromFolder,
      loadData
    }}>
      {children}
    </SessionsContext.Provider>
  );
}

export const useSessions = () => useContext(SessionsContext);