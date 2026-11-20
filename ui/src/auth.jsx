import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getToken, setToken } from './api.js';
import { toast } from './toast.js';
import { asArray } from './util.js';

const Ctx = createContext(null);
export const useAuth = () => useContext(Ctx);

// GET /api/me -> user fields + teams:[{teamId, teamName, role}] (also tolerates {user:{...}, teams:[...]}).
function normaliseMe(m) {
  if (!m || typeof m !== 'object') return null;
  const u = m.user && typeof m.user === 'object' ? { ...m.user, teams: m.teams || m.user.teams } : { ...m };
  u.teams = asArray(u.teams).map((t) => ({
    teamId: t.teamId || t.id,
    teamName: t.teamName || t.name || t.teamId || t.id,
    role: t.role,
  }));
  return u;
}

export function AuthProvider({ children }) {
  const [me, setMe] = useState(null);
  const [teams, setTeams] = useState([]);
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(!!getToken());

  const load = useCallback(async () => {
    if (!getToken()) {
      setMe(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const u = normaliseMe(await api.get('/api/me'));
      const [t, us] = await Promise.all([
        api.get('/api/teams').catch(() => []),
        api.get('/api/auth/users').catch(() => []),
      ]);
      let teamList = asArray(t);
      if (!teamList.length && u) teamList = u.teams.map((x) => ({ id: x.teamId, name: x.teamName, members: [] }));
      setTeams(teamList);
      setUsers(asArray(us));
      setMe(u);
    } catch (e) {
      setMe(null);
      if (e.status !== 401) toast.error(e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const login = useCallback(
    async (username) => {
      const r = await api.post('/api/auth/login', { username });
      if (!r || !r.token) throw new Error('Login did not return a token.');
      setToken(r.token);
      await load();
    },
    [load]
  );

  const logout = useCallback(() => {
    setToken(null);
    setMe(null);
  }, []);

  const value = useMemo(() => {
    const isPlatformAdmin = !!(me && me.platformAdmin);
    const isAdminOf = (teamId) => {
      if (!me || !teamId) return false;
      if (isPlatformAdmin) return true;
      if (me.teams.some((t) => t.teamId === teamId && t.role === 'admin')) return true;
      const team = teams.find((t) => t.id === teamId);
      return !!team && asArray(team.members).some((m) => m.userId === me.id && m.role === 'admin');
    };
    const teamName = (id) => {
      if (!id) return '';
      const t = teams.find((x) => x.id === id);
      if (t && t.name) return t.name;
      const mt = me && me.teams.find((x) => x.teamId === id);
      return (mt && mt.teamName) || id;
    };
    const userName = (id) => {
      if (!id) return '';
      const u = users.find((x) => x.id === id || x.username === id);
      return (u && (u.displayName || u.username)) || id;
    };
    return {
      me, teams, users, loading, login, logout, reload: load,
      isPlatformAdmin, isAdminOf, teamName, userName,
      adminTeams: teams.filter((t) => isAdminOf(t.id)),
    };
  }, [me, teams, users, loading, login, logout, load]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
