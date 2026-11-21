import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { api, getToken } from './api.js';
import { useAuth } from './auth.jsx';

const ThemeCtx = createContext(null);
export const useTheme = () => useContext(ThemeCtx);

// The theme is a user preference stored on the server; this cache only makes the
// first paint fast (and keeps the choice on the login page, before we know who it is).
const CACHE_KEY = 'alerting.theme';
const VALID = new Set(['light', 'dark']);

function normaliseTheme(value) {
  return VALID.has(value) ? value : null;
}

function systemTheme() {
  try {
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

function readCache(username) {
  try {
    const stored = JSON.parse(localStorage.getItem(CACHE_KEY) || 'null');
    if (typeof stored === 'string') return normaliseTheme(stored);
    if (stored && typeof stored === 'object') {
      return normaliseTheme(username ? stored[username] : stored.last);
    }
  } catch {
    /* ignore malformed cache */
  }
  return null;
}

function writeCache(username, theme) {
  try {
    let stored = {};
    try {
      const parsed = JSON.parse(localStorage.getItem(CACHE_KEY) || 'null');
      if (parsed && typeof parsed === 'object') stored = parsed;
    } catch {
      /* start a fresh cache */
    }
    if (username) stored[username] = theme;
    stored.last = theme;
    localStorage.setItem(CACHE_KEY, JSON.stringify(stored));
  } catch {
    /* storage may be unavailable */
  }
}

function applyTheme(theme) {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
}

function initialTheme() {
  const theme = readCache() || systemTheme();
  applyTheme(theme);
  return theme;
}

export function ThemeProvider({ children }) {
  const { me } = useAuth();
  const username = (me && me.username) || null;
  const [theme, setThemeState] = useState(initialTheme);
  const userChanged = useRef(false);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  // Load the signed-in user's saved theme from their preferences.
  useEffect(() => {
    userChanged.current = false;
    if (!username || !getToken()) return undefined;
    const cached = readCache(username);
    if (cached) setThemeState(cached);
    let cancelled = false;
    api.get('/api/me/preferences')
      .then((prefs) => {
        if (cancelled || userChanged.current) return;
        const saved = normaliseTheme(prefs && prefs.theme);
        if (saved) {
          setThemeState(saved);
          writeCache(username, saved);
        }
      })
      .catch(() => {
        /* fall back to the cached or system theme */
      });
    return () => {
      cancelled = true;
    };
  }, [username]);

  const setTheme = useCallback(
    (next) => {
      const value = normaliseTheme(next) || 'light';
      userChanged.current = true;
      setThemeState(value);
      writeCache(username, value);
      if (getToken()) api.put('/api/me/preferences', { theme: value }).catch(() => {});
    },
    [username]
  );

  const toggle = useCallback(() => {
    setTheme(theme === 'dark' ? 'light' : 'dark');
  }, [setTheme, theme]);

  const value = useMemo(() => ({ theme, isDark: theme === 'dark', setTheme, toggle }), [theme, setTheme, toggle]);

  return <ThemeCtx.Provider value={value}>{children}</ThemeCtx.Provider>;
}
