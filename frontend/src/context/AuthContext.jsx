import React, { createContext, useContext, useEffect, useState } from "react";
import { authApi } from "../services/api.js";

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [user, setUser]       = useState(null);
  const [token, setToken]     = useState(null);
  const [authReady, setAuthReady] = useState(false);  // true once session check is done

  /**
   * On mount, call GET /auth/me to restore the session from the HttpOnly cookie.
   */
  useEffect(() => {
    authApi.me()
      .then((data) => {
        if (data?.user) {
          setUser(data.user);
        }
      })
      .catch(() => {
        // 401 = no valid cookie — user is not logged in
      })
      .finally(() => {
        setAuthReady(true);
      });

    const handleAuthExpired = () => {
      // Clear local state; ProtectedRoute will automatically redirect to /login
      setUser(null);
      setToken(null);
    };

    window.addEventListener('auth-expired', handleAuthExpired);
    const onProfileUpdated = (e) => { if (e?.detail) setUser((prev) => ({ ...prev, ...e.detail })); };
    window.addEventListener('profile-updated', onProfileUpdated);

    return () => {
      window.removeEventListener('auth-expired', handleAuthExpired);
      window.removeEventListener('profile-updated', onProfileUpdated);
    };
  }, []);

  /** Called after a successful login or register */
  const login = (userData, accessToken) => {
    setUser(userData);
    setToken(accessToken ?? null);
    setAuthReady(true);
  };

  // Persist user to localStorage for frontend-only development convenience
  useEffect(() => {
    try {
      if (user) localStorage.setItem('mock_user', JSON.stringify(user));
      else localStorage.removeItem('mock_user');
    } catch {}
  }, [user]);

  /** Called on logout — clears backend cookies then local state */
  const logout = async () => {
    try {
      await authApi.logout();
    } catch {
      // Ignore network errors — clear local state regardless
    }
    setUser(null);
    setToken(null);
    setAuthReady(true);
  };

  /** Called from Profile page to update user info locally */
  const updateUser = (updatedUser) => {
    setUser((prev) => ({ ...prev, ...updatedUser }));
  };

  // If auth check fails and there is a local mock user, load it so UI remains usable
  useEffect(() => {
    if (!authReady) return;
    if (!user) {
      try {
        const raw = localStorage.getItem('mock_user');
        if (raw) setUser(JSON.parse(raw));
      } catch {}
    }
  }, [authReady]);

  const isAuthenticated = !!user;

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        isAuthenticated,
        authReady,
        login,
        logout,
        updateUser,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
