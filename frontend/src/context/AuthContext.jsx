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
    return () => window.removeEventListener('auth-expired', handleAuthExpired);
  }, []);

  /** Called after a successful login or register */
  const login = (userData, accessToken) => {
    setUser(userData);
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

  /** Called from Profile page to update user info locally */
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
