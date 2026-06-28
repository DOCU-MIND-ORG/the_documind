import { createContext, useContext, useEffect, useState } from "react";
import { authService } from "../services/authService.js";

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [user, setUser]       = useState(null);
  const [token, setToken]     = useState(null);
  const [authReady, setAuthReady] = useState(false);  

  useEffect(() => {
    authService.me()
      .then((data) => {
        if (data?.user) {
          setUser(data.user);
        }
      })
      .catch(() => {
        // 401 means user is not logged in
      })
      .finally(() => {
        setAuthReady(true);
      });

    const handleAuthExpired = () => {
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


  const login = (userData, accessToken) => {
    setUser(userData);
    setToken(accessToken ?? null);
    setAuthReady(true);
  };

  useEffect(() => {
    try {
      if (user) localStorage.setItem('mock_user', JSON.stringify(user));
      else localStorage.removeItem('mock_user');
    } catch {}
  }, [user]);

  
  const logout = async () => {
    try {
      await authService.logout();
    } catch {     
    }
    setUser(null);
    setToken(null);
    setAuthReady(true);
  };

  const updateUser = (updatedUser) => {
    setUser((prev) => ({ ...prev, ...updatedUser }));
  };

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
