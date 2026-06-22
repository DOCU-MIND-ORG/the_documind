import { useEffect } from 'react';

export default function Toast({ message, type = 'info', onClose, duration = 4000 }) {
  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(onClose, duration);
    return () => clearTimeout(timer);
  }, [message, duration, onClose]);

  if (!message) return null;

  const icons = { success: '✅', error: '❌', info: 'ℹ️', warning: '⚠️' };

  const themeClasses = {
    success: 'bg-emerald-950/80 border-l-4 border-l-emerald-500 border border-white/5 text-emerald-100',
    error: 'bg-red-950/80 border-l-4 border-l-red-500 border border-white/5 text-red-100',
    info: 'bg-blue-950/80 border-l-4 border-l-blue-500 border border-white/5 text-blue-100',
    warning: 'bg-amber-950/80 border-l-4 border-l-amber-500 border border-white/5 text-amber-100',
  };

  return (
    <div className={`flex items-center gap-3 px-5 py-4 rounded-xl shadow-2xl min-w-[280px] max-w-[420px] animate-fade-in-up text-sm font-medium backdrop-blur-md ${themeClasses[type] || themeClasses.info}`} role="alert">
      <span className="text-base shrink-0">{icons[type]}</span>
      <span className="flex-1 leading-snug">{message}</span>
      <button className="text-white/60 hover:text-white shrink-0 flex items-center justify-center p-1 rounded hover:bg-white/10 transition-colors cursor-pointer" onClick={onClose} aria-label="Dismiss">✕</button>
    </div>
  );
}
