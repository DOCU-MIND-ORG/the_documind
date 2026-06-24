import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { sessionService } from '../services/sessionService.js';

const SessionsContext = createContext(null);

export function SessionsProvider({ children }) {
  const [sessions, setSessions] = useState([]);
  const [loading, setLoading] = useState(true);

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