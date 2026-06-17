import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { sessionService } from '../services/sessionService.js';

const SessionsContext = createContext(null);

// This context's only job is the sidebar list (id, title, lastUpdated, etc).
// It deliberately knows nothing about message content or streaming state —
// that all lives in Chat.jsx's own reducer now. Keeping this context "dumb"
// is what lets Chat.jsx be the single owner of conversation state without
// fighting two sources of truth.

export function SessionsProvider({ children }) {
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading]   = useState(true);

  useEffect(() => {
    sessionService.getAll()
      .then(data => setSessions(data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const addSession = useCallback((s) => {
    setSessions(prev => [s, ...prev.filter(p => p.sessionId !== s.sessionId)]);
  }, []);

  const removeSession = useCallback((id) => {
    setSessions(prev => prev.filter(s => s.sessionId !== id));
  }, []);

  // Optional but useful in production apps: let Chat.jsx push title/timestamp
  // updates back to the sidebar without a full refetch (e.g. after the
  // backend renames a session based on conversation content).
  const updateSession = useCallback((id, patch) => {
    setSessions(prev => prev.map(s => s.sessionId === id ? { ...s, ...patch } : s));
  }, []);

  return (
    <SessionsContext.Provider value={{ sessions, loading, addSession, removeSession, updateSession }}>
      {children}
    </SessionsContext.Provider>
  );
}

export const useSessions = () => useContext(SessionsContext);