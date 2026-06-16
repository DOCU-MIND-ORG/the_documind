/**
 * AuthContext.jsx
 *
 * FIX: /auth/me in AuthController returns UserDto directly as JSON,
 * not { user: UserDto }. So data.user is always undefined.
 * Fixed to use data directly (or data.user as fallback).
 */
import React, { createContext, useContext, useEffect, useState } from "react";
import { authApi } from "../services/api.js";

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [user, setUser]           = useState(null);
  const [token, setToken]         = useState(null);
  const [authReady, setAuthReady] = useState(false);

  useEffect(() => {
    authApi.me()
      .then((data) => {
        if (data) {
          // FIX: backend returns UserDto directly, not { user: UserDto }
          // Support both shapes in case backend changes
          setUser(data.user ?? data);
        }
      })
      .catch(() => {
        // 401 = no valid cookie — user is not logged in, that's fine
      })
      .finally(() => {
        setAuthReady(true);
      });

    const handleAuthExpired = () => {
      setUser(null);
      setToken(null);
    };

    window.addEventListener('auth-expired', handleAuthExpired);
    return () => window.removeEventListener('auth-expired', handleAuthExpired);
  }, []);

  /** Called after successful login or register */
  const login = (userData, accessToken) => {
    // FIX: same — support both { user: ... } and flat UserDto
    setUser(userData?.user ?? userData);
    setToken(accessToken ?? null);
    setAuthReady(true);
  };

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

  /** Update user info locally (used by Settings/Profile page) */
  const updateUser = (updatedUser) => {
    setUser((prev) => ({ ...prev, ...updatedUser }));
  };

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
