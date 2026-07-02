import { createContext, useContext, useEffect, useState } from 'react';

const ThemeContext = createContext(null);

function getInitialTheme() {
  try {
    const stored = localStorage.getItem('theme');
    if (stored === 'dark' || stored === 'light' || (stored && stored.startsWith('custom:'))) return stored;
  } catch (_) { /* ignore */ }
  if (typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

export function ThemeProvider({ children }) {
  const [theme, setThemeState] = useState(getInitialTheme);

  useEffect(() => {
    const html = document.documentElement;
    
    // Clear inline styles if previously set by custom theme
    const vars = [
      '--color-bg-base', '--color-bg-surface', '--color-bg-sidebar', '--color-bg-elevated',
      '--color-text-primary', '--color-text-secondary', '--color-text-tertiary', '--color-text-inverse',
      '--color-border', '--color-border-strong', '--color-bg-hover', '--color-bg-active', '--color-bg-input'
    ];
    vars.forEach(v => html.style.removeProperty(v));

    if (theme.startsWith('custom:')) {
      html.classList.add('custom');
      html.classList.remove('light', 'dark');
      
      const hex = theme.split(':')[1];
      if (/^#[0-9A-Fa-f]{6}$/i.test(hex)) {
        let r = parseInt(hex.slice(1, 3), 16);
        let g = parseInt(hex.slice(3, 5), 16);
        let b = parseInt(hex.slice(5, 7), 16);
        
        const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        const isLight = luminance > 0.5;
        
        if (isLight) {
          html.classList.add('light');
          html.classList.remove('dark');
        } else {
          html.classList.add('dark');
          html.classList.remove('light');
        }
      
        const textPrimary = isLight ? '#0f172a' : '#ffffff';
        const textSecondary = isLight ? 'rgba(15, 23, 42, 0.7)' : 'rgba(255, 255, 255, 0.7)';
        const textTertiary = isLight ? 'rgba(15, 23, 42, 0.5)' : 'rgba(255, 255, 255, 0.5)';
        const textInverse = isLight ? '#ffffff' : '#0f172a';
        
        const adjust = isLight ? 15 : -15;
        const surfaceHex = `#${Math.max(0, Math.min(255, r + adjust)).toString(16).padStart(2, '0')}${Math.max(0, Math.min(255, g + adjust)).toString(16).padStart(2, '0')}${Math.max(0, Math.min(255, b + adjust)).toString(16).padStart(2, '0')}`;
      
        const border = isLight ? 'rgba(15, 23, 42, 0.1)' : 'rgba(255, 255, 255, 0.1)';
        const borderStrong = isLight ? 'rgba(15, 23, 42, 0.2)' : 'rgba(255, 255, 255, 0.2)';
        const bgHover = isLight ? 'rgba(15, 23, 42, 0.05)' : 'rgba(255, 255, 255, 0.05)';
        const bgActive = isLight ? 'rgba(15, 23, 42, 0.1)' : 'rgba(255, 255, 255, 0.1)';
      
        html.style.setProperty('--color-bg-base', hex);
        html.style.setProperty('--color-bg-surface', surfaceHex);
        html.style.setProperty('--color-bg-sidebar', surfaceHex);
        html.style.setProperty('--color-bg-elevated', surfaceHex);
        html.style.setProperty('--color-text-primary', textPrimary);
        html.style.setProperty('--color-text-secondary', textSecondary);
        html.style.setProperty('--color-text-tertiary', textTertiary);
        html.style.setProperty('--color-text-inverse', textInverse);
        html.style.setProperty('--color-border', border);
        html.style.setProperty('--color-border-strong', borderStrong);
        html.style.setProperty('--color-bg-hover', bgHover);
        html.style.setProperty('--color-bg-active', bgActive);
        html.style.setProperty('--color-bg-input', bgHover);
      }
    } else if (theme === 'dark') {
      html.classList.add('dark');
      html.classList.remove('light', 'custom');
    } else {
      html.classList.remove('dark', 'custom');
      html.classList.add('light');
    }
    try { localStorage.setItem('theme', theme); } catch (_) { /* ignore */ }
  }, [theme]);

  const setTheme = (value) => {
    if (value === 'dark' || value === 'light' || value.startsWith('custom:')) setThemeState(value);
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
