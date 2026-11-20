import React, { useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import { api, getToken } from './api.js';
import { toast } from './toast.js';
import { asArray } from './util.js';
import { Toasts } from './components/ui.jsx';
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
  ['/admin', 'Admin'],
];

function roleSummary(me) {
  if (!me) return '';
  if (me.platformAdmin) return 'Platform admin';
  const parts = asArray(me.teams).map((t) => `${t.teamName} ${t.role || 'member'}`);
  return parts.length ? parts.join(', ') : 'No team';
}

function TopBar() {
  const { me, users, login } = useAuth();
  const navigate = useNavigate();
  const [allUsers, setAllUsers] = useState(users);

  useEffect(() => {
    if (users.length) setAllUsers(users);
    else api.get('/api/auth/users').then((u) => setAllUsers(asArray(u))).catch(() => {});
  }, [users]);

  const switchTo = async (username) => {
    if (!username) return;
    if (username === '__login') return navigate('/login');
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
        {NAV.map(([to, label]) => (
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
    <>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Shell><Feed /></Shell>} />
        <Route path="/alerts/:id" element={<Shell><AlertDetail /></Shell>} />
        <Route path="/sources" element={<Shell><Sources /></Shell>} />
        <Route path="/rules" element={<Shell><Rules /></Shell>} />
        <Route path="/runbooks" element={<Shell><Runbooks /></Shell>} />
        <Route path="/preferences" element={<Shell><Preferences /></Shell>} />
        <Route path="/admin" element={<Shell><Admin /></Shell>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <Toasts />
    </>
  );
}
