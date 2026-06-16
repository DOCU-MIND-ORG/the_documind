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
// (App.css removed)

/** Show a full-page spinner while the /auth/me session check completes */
function AuthLoading() {
  return (
    <div className="flex items-center justify-center h-screen bg-[#0f172a]">
      <div className="text-center text-[#94a3b8]">
        <div className="w-12 h-12 border-3 border-blue-500/30 border-t-blue-500 rounded-full animate-spin mx-auto mb-4" />
        <p className="text-sm">Loading…</p>
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
