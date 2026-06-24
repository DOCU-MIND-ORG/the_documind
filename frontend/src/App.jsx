import { useState } from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './context/AuthContext.jsx';
import { SessionsProvider } from './context/SessionsContext.jsx';
import AppSidebar from './components/AppSidebar.jsx';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import Chat from './pages/Chat.jsx';
import Settings from './pages/Settings.jsx';
import Attachments from './pages/Attachments.jsx';

function AuthLoading() {
  return (
    <div className="flex items-center justify-center h-screen t-bg-main">
      <div className="flex flex-col items-center gap-4 t-text-muted">
        <div className="w-8 h-8 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />
        <p className="text-sm">Loading…</p>
      </div>
    </div>
  );
}

function GuestRoute({ children }) {
  const { isAuthenticated, authReady } = useAuth();
  if (!authReady) return <AuthLoading />;
  if (isAuthenticated) return <Navigate to="/dashboard" replace />;
  return children;
}

function ProtectedLayout() {
  const { isAuthenticated, authReady } = useAuth();
  const [expanded, setExpanded] = useState(true);
  const [mobileOpen, setMobileOpen] = useState(false);

  if (!authReady) return <AuthLoading />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;

  return (
    <SessionsProvider>
      <div
        className="flex h-screen t-bg-main t-text-main overflow-hidden"
        style={{ fontFamily: "'Inter', system-ui, sans-serif" }}
        onClick={() => mobileOpen && setMobileOpen(false)}
      >
        <AppSidebar
          expanded={expanded}
          setExpanded={setExpanded}
          mobileOpen={mobileOpen}
          setMobileOpen={setMobileOpen}
        />
        <div className="flex-1 flex flex-col overflow-hidden">
          <Outlet context={{ openMobileSidebar: () => setMobileOpen(true) }} />
        </div>
      </div>
    </SessionsProvider>
  );
}

function RootRedirect() {
  const { isAuthenticated, authReady } = useAuth();
  if (!authReady) return <AuthLoading />;
  return <Navigate to={isAuthenticated ? '/dashboard' : '/login'} replace />;
}

export default function App() {
  return (
    <Router>
      <Routes>
        <Route path="/" element={<RootRedirect />} />
        <Route path="/login" element={<GuestRoute><Login /></GuestRoute>} />
        <Route path="/register" element={<GuestRoute><Register /></GuestRoute>} />

        <Route element={<ProtectedLayout />}>
          <Route path="/dashboard" element={<Chat />} />
          <Route path="/chat/:sessionId" element={<Chat />} />
          <Route path="/chat/:sessionId/attachments" element={<Attachments />} />
          <Route path="/settings" element={<Settings />} />
        </Route>

        <Route path="*" element={<RootRedirect />} />
      </Routes>
    </Router>
  );
}
