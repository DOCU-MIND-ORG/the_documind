/**
 * ThemeContext — the professional approach used by Claude, Notion, Linear, etc.
 *
 * Strategy:
 *   - Adds/removes class "dark" on <html> (document.documentElement)
 *   - CSS custom properties are defined in :root (light) and html.dark (dark)
 *   - Tailwind's dark: variant also activates from html.dark
 *   - Persists preference to localStorage
 *   - Respects system preference on first visit
 */
import { createContext, useContext, useEffect, useState } from 'react';

const ThemeContext = createContext(null);

function getInitialTheme() {
  try {
    const stored = localStorage.getItem('theme');
    if (stored === 'dark' || stored === 'light') return stored;
  } catch (_) { /* ignore */ }
  // Respect OS preference on first visit
  if (typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(getInitialTheme);

  // Apply class to <html> whenever theme changes
  useEffect(() => {
    const html = document.documentElement;
    if (theme === 'dark') {
      html.classList.add('dark');
      html.classList.remove('light');
    } else {
      html.classList.remove('dark');
      html.classList.add('light');
    }
    try { localStorage.setItem('theme', theme); } catch (_) { /* ignore */ }
  }, [theme]);

  const setTheme = (value) => {
    if (value === 'dark' || value === 'light') setThemeState(value);
  };

  const toggle = () => setThemeState(t => (t === 'dark' ? 'light' : 'dark'));

  return (
    <ThemeContext.Provider value={{ theme, setTheme, toggle }}>
      {children}
    </ThemeContext.Provider>
  );
}

export const useTheme = () => {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside ThemeProvider');
  return ctx;
};

export default ThemeContext;
