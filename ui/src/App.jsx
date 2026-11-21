import React, { useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import { api, getToken } from './api.js';
import { toast } from './toast.js';
import { useTheme } from './theme.jsx';
import { asArray } from './util.js';
import { Empty, Toasts } from './components/ui.jsx';
import { UnsavedChangesProvider, useLeaveGuard } from './unsaved.jsx';
import Login from './pages/Login.jsx';
import Feed from './pages/Feed.jsx';
import AlertDetail from './pages/AlertDetail.jsx';
import Sources from './pages/Sources.jsx';
import Rules from './pages/Rules.jsx';
import Runbooks from './pages/Runbooks.jsx';
import Preferences from './pages/Preferences.jsx';
import Admin from './pages/Admin.jsx';

const NAV = [
  ['/', 'Alerts'],
  ['/rules', 'Rules'],
  ['/sources', 'Sources'],
  ['/runbooks', 'Runbooks'],
  ['/preferences', 'My preferences'],
  ['/admin', 'Admin', 'anyAdmin'],
];

// Admin page: platform admins (channel failure injection) and team admins (their team's notification policy).
const isAnyAdmin = (me) => !!me && (!!me.platformAdmin || asArray(me.teams).some((t) => t.role === 'admin'));

function roleSummary(me) {
  if (!me) return '';
  if (me.platformAdmin) return 'Platform admin';
  const parts = asArray(me.teams).map((t) => `${t.teamName} ${t.role || 'member'}`);
  return parts.length ? parts.join(', ') : 'No team';
}

function ThemeIcon({ dark }) {
  return dark ? (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41" />
    </svg>
  ) : (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}

function TopBar() {
  const { me, users, login } = useAuth();
  const { theme, toggle: toggleTheme } = useTheme();
  const navigate = useNavigate();
  const guard = useLeaveGuard();
  const [allUsers, setAllUsers] = useState(users);

  useEffect(() => {
    if (users.length) setAllUsers(users);
    else api.get('/api/auth/users').then((u) => setAllUsers(asArray(u))).catch(() => {});
  }, [users]);

  const switchTo = (username) => {
    if (!username) return;
    if (username === '__login') return navigate('/login');
    // Switching user reloads every page, so unsaved edits would be lost: ask first.
    guard(() => doSwitch(username));
  };

  const doSwitch = async (username) => {
    try {
      await login(username);
      toast.success(`Now signed in as ${username}`);
    } catch (e) {
      toast.error(e);
    }
  };

  return (
    <header className="topbar">
      <div className="brand">
        <span className="brand-dot" /> Alerting
      </div>
      <nav className="nav">
        {NAV.filter(([, , only]) => !only || (only === 'anyAdmin' ? isAnyAdmin(me) : me[only])).map(([to, label]) => (
          <NavLink key={to} to={to} end={to === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
            {label}
          </NavLink>
        ))}
      </nav>
      <div className="userbox">
        <div className="who">
          <div className="who-name">{me.displayName || me.username || me.id}</div>
          <div className="who-role">{roleSummary(me)}</div>
        </div>
        <button
          type="button"
          className="theme-toggle"
          onClick={toggleTheme}
          aria-pressed={theme === 'dark'}
          aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          <ThemeIcon dark={theme === 'dark'} />
        </button>
        <label className="switch-user">
          <span className="sr-only">Switch user</span>
          <select value="" onChange={(e) => switchTo(e.target.value)} aria-label="Switch user">
            <option value="">Switch user…</option>
            {allUsers
              .filter((u) => u.id !== me.id)
              .map((u) => (
                <option key={u.id || u.username} value={u.username}>
                  {u.displayName || u.username}
                </option>
              ))}
            <option value="__login">Go to login page</option>
          </select>
        </label>
      </div>
    </header>
  );
}

function PlatformAdminOnly({ children }) {
  const { me } = useAuth();
  if (!isAnyAdmin(me)) {
    return <Empty title="Not allowed">Only team admins and platform admins can open the Admin page.</Empty>;
  }
  return children;
}

function Shell({ children }) {
  const { me, loading } = useAuth();
  const location = useLocation();
  if (loading && !me) return <div className="page"><div className="empty">Loading…</div></div>;
  if (!me || !getToken()) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return (
    <>
      <TopBar />
      {/* key: remount pages when the user changes so every list reloads with the new permissions */}
      <main className="page" key={me.id}>
        {children}
      </main>
    </>
  );
}

export default function App() {
  return (
    <UnsavedChangesProvider>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Shell><Feed /></Shell>} />
        <Route path="/alerts/:id" element={<Shell><AlertDetail /></Shell>} />
        <Route path="/sources" element={<Shell><Sources /></Shell>} />
        <Route path="/rules" element={<Shell><Rules /></Shell>} />
        <Route path="/runbooks" element={<Shell><Runbooks /></Shell>} />
        <Route path="/preferences" element={<Shell><Preferences /></Shell>} />
        <Route path="/admin" element={<Shell><PlatformAdminOnly><Admin /></PlatformAdminOnly></Shell>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <Toasts />
    </UnsavedChangesProvider>
  );
}
