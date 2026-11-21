import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { asArray } from '../util.js';
import { Empty, ErrorBox, Help } from '../components/ui.jsx';

export default function Login() {
  const { login, me } = useAuth();
  const navigate = useNavigate();
  const [users, setUsers] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(null);

  const load = () => {
    setError(null);
    api.get('/api/auth/users').then((u) => setUsers(asArray(u))).catch((e) => { setUsers([]); setError(e.message); });
  };
  useEffect(load, []);

  const choose = async (u) => {
    setBusy(u.username);
    setError(null);
    try {
      await login(u.username);
      navigate('/', { replace: true });
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="login-wrap">
      <div className="card login-card">
        <h1>Alerting</h1>
        <p className="muted">
          Choose who to sign in as.
          <Help text="Each person belongs to different teams and sees only what their role allows." />
        </p>
        <ErrorBox>{error}</ErrorBox>
        {users === null && <Empty>Loading users…</Empty>}
        {users && users.length === 0 && (
          <Empty title="No users to show">
            The server returned no users. <button type="button" className="btn small" onClick={load}>Try again</button>
          </Empty>
        )}
        <div className="user-list">
          {(users || []).map((u) => (
            <button type="button" key={u.id || u.username} className="user-pick" disabled={!!busy} onClick={() => choose(u)}>
              <span className="avatar">{String(u.displayName || u.username || '?').slice(0, 1).toUpperCase()}</span>
              <span>
                <strong>{u.displayName || u.username}</strong>
                <span className="muted"> @{u.username}</span>
                {me && me.id === u.id && <span className="badge">current</span>}
              </span>
              <span className="muted">{busy === u.username ? 'Signing in…' : 'Sign in'}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
