import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import { useAuth } from './context/AuthContext.jsx';

// Auth Pages
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';

// Main Pages
import Dashboard from './pages/Dashboard.jsx';
import Settings from './pages/Settings.jsx';

// Styles
import './App.css';

/** Show a full-page spinner while the /auth/me session check completes */
function AuthLoading() {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      height: '100vh', background: 'var(--bg-base, #0f172a)',
    }}>
      <div style={{ textAlign: 'center', color: 'var(--text-muted, #94a3b8)' }}>
        <div style={{
          width: 48, height: 48, border: '3px solid rgba(59,130,246,0.3)',
          borderTopColor: 'var(--primary, #3b82f6)', borderRadius: '50%',
          animation: 'spin 0.8s linear infinite', margin: '0 auto 16px',
        }} />
        <p style={{ fontSize: '0.875rem' }}>Loading…</p>
      </div>
    </div>
  );
}

/** Redirect authenticated users away from login/register */
function GuestRoute({ children }) {
  const { isAuthenticated, authReady } = useAuth();
  if (!authReady) return <AuthLoading />;
  if (isAuthenticated) return <Navigate to="/dashboard" replace />;
  return children;
}

/** Redirect unauthenticated users to login */
function ProtectedRoute({ children }) {
  const { isAuthenticated, authReady } = useAuth();
  if (!authReady) return <AuthLoading />;
  return isAuthenticated ? children : <Navigate to="/login" replace />;
}

/** Root "/" — redirect based on role */
function RootRedirect() {
  const { isAuthenticated, authReady } = useAuth();
  if (!authReady) return <AuthLoading />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <Navigate to="/dashboard" replace />;
}

function App() {
  return (
    <Router>
      <Routes>
        {/* Root — redirect based on auth */}
        <Route path="/" element={<RootRedirect />} />

        {/* Auth Routes — redirect to dashboard if already logged in */}
        <Route path="/login"    element={<GuestRoute><Login /></GuestRoute>} />
        <Route path="/register" element={<GuestRoute><Register /></GuestRoute>} />

        {/* Protected Dashboard Route */}
        <Route path="/dashboard" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
        <Route path="/settings" element={<ProtectedRoute><Settings /></ProtectedRoute>} />

        {/* Fallback */}
        <Route path="*" element={<RootRedirect />} />
      </Routes>
    </Router>
  );
}

export default App;
